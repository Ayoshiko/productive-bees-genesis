package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class BeeDesignerOperationMinecraftTest {
	private static final UUID OWNER = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
	private static final UUID TOKEN = UUID.fromString("11234567-89ab-cdef-0123-456789abcdef");

	@Test
	void originalTokenSurvivesLaterPublicationsAndRejectsDifferentOwnerOrRequest(@TempDir Path world) throws IOException {
		JsonObject document = BeeDesignerDocument.create("test:retained_operation");
		Map<String, String> files = BeeDesignerCompiler.files(Map.of("test:retained_operation", document.toString()));
		BeeDesignerOperation identity = identity(document, TOKEN);
		var revision = BeeDesignerStore.write(world, files, false, identity);
		assertEquals(TOKEN.toString(), BeeDesignerStore.inspect(world).get("token").getAsString());
		assertEquals("prepared", BeeDesignerStore.operation(world, TOKEN, OWNER, identity.requestDigest()).get("phase").getAsString());
		BeeDesignerStore.install(revision);
		BeeDesignerStore.complete(revision, List.of());
		var later = BeeDesignerStore.write(world, files, false);
		BeeDesignerStore.install(later);
		BeeDesignerStore.complete(later, List.of(revision.packId()));
		JsonObject retained = BeeDesignerStore.operation(world, TOKEN, OWNER, identity.requestDigest());
		assertEquals("committed", retained.get("phase").getAsString());
		assertEquals(OWNER.toString(), retained.get("owner").getAsString());
		assertEquals("new", retained.getAsJsonObject("baselines").get("test:retained_operation").getAsString());
		assertEquals(BeeDesignerDocument.fingerprint(document),
				retained.getAsJsonObject("fingerprints").get("test:retained_operation").getAsString());
		assertThrows(IOException.class, () -> BeeDesignerStore.write(world, files, false, identity));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerStore.operation(world, TOKEN,
				UUID.fromString("21234567-89ab-cdef-0123-456789abcdef"), identity.requestDigest()));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerStore.operation(world, TOKEN, OWNER, "a".repeat(64)));
		UUID unknown = UUID.fromString("31234567-89ab-cdef-0123-456789abcdef");
		assertNull(BeeDesignerStore.operation(world, unknown, OWNER, ""));
		JsonObject unknownReceipt = BeeDesignerOperation.receipt(BeeDesignerOperation.unknown(unknown));
		assertEquals("unknown", unknownReceipt.get("phase").getAsString());
		assertTrue(unknownReceipt.get("uncertain").getAsBoolean());
		assertFalse(unknownReceipt.get("done").getAsBoolean());
	}

	@Test
	void reconcileUpdatesOriginalTokenForActivatedAndRejectedOutcomes(@TempDir Path world) throws IOException {
		JsonObject document = BeeDesignerDocument.create("test:reconciled_operation");
		Map<String, String> files = BeeDesignerCompiler.files(Map.of("test:reconciled_operation", document.toString()));
		BeeDesignerOperation identity = identity(document, TOKEN);
		var revision = BeeDesignerStore.write(world, files, false, identity);
		BeeDesignerStore.install(revision);
		BeeDesignerStore.reject(revision, true, "reload response lost");
		assertEquals("uncertain", BeeDesignerStore.operation(world, TOKEN, OWNER, "").get("phase").getAsString());
		BeeDesignerStore.reconcile(world, BeeDesignerStore.audit(world, List.of(revision.packId())));
		assertEquals("committed", BeeDesignerStore.operation(world, TOKEN, OWNER, "").get("phase").getAsString());
		UUID nextToken = UUID.fromString("41234567-89ab-cdef-0123-456789abcdef");
		var rejected = BeeDesignerStore.write(world, files, false, identity(document, nextToken));
		BeeDesignerStore.install(rejected);
		BeeDesignerStore.reject(rejected, true, "activation unknown");
		BeeDesignerStore.reconcile(world, BeeDesignerStore.audit(world, List.of(revision.packId())));
		assertEquals("rejected", BeeDesignerStore.operation(world, nextToken, OWNER, "").get("phase").getAsString());
		assertEquals("committed", BeeDesignerStore.operation(world, TOKEN, OWNER, "").get("phase").getAsString());
		assertEquals(nextToken.toString(), BeeDesignerStore.inspect(world).get("token").getAsString());
	}

	@Test
	void latestJournalPreservesIdentityWhenRetainedRecordWriteWasInterrupted(@TempDir Path world) throws IOException {
		JsonObject document = BeeDesignerDocument.create("test:journal_identity");
		Map<String, String> files = BeeDesignerCompiler.files(Map.of("test:journal_identity", document.toString()));
		BeeDesignerOperation identity = identity(document, TOKEN);
		var revision = BeeDesignerStore.write(world, files, false, identity);
		Path sidecar = revision.source().resolveSibling(revision.source().getFileName().toString().replace(".zip", ".json"));
		Files.move(sidecar, sidecar.resolveSibling("retained-interrupted.fixture"));
		assertEquals("prepared", BeeDesignerStore.operation(world, TOKEN, OWNER, "").get("phase").getAsString());
		BeeDesignerStore.reconcile(world, BeeDesignerStore.audit(world, List.of()));
		assertEquals("rejected", BeeDesignerStore.operation(world, TOKEN, OWNER, "").get("phase").getAsString());
		assertTrue(Files.exists(sidecar));
	}

	@Test
	void normalizedFinalDocumentIsReadFromRevisionInBoundedChunks(@TempDir Path world) throws IOException {
		JsonObject staged = BeeDesignerDocument.create("test:normalized_operation");
		staged.addProperty("opaque", "\\\"".repeat(4000));
		JsonObject normalized = staged.deepCopy();
		normalized.getAsJsonObject("definition").addProperty("normalized_extension", "authoritative");
		Map<String, String> projects = Map.of("test:normalized_operation", normalized.toString());
		Map<String, String> files = BeeDesignerCompiler.files(projects);
		BeeDesignerOperation identity = BeeDesignerOperation.create(TOKEN, OWNER, BeeDesignerDocument.fingerprint(staged), "import",
				Map.of("test:normalized_operation", "new"), projects);
		var revision = BeeDesignerStore.write(world, files, false, identity);
		BeeDesignerStore.install(revision);
		BeeDesignerStore.complete(revision, List.of());
		JsonObject record = BeeDesignerStore.operation(world, TOKEN, OWNER, "");
		StringBuilder joined = new StringBuilder();
		int offset = 0;
		int pieces = 0;
		int total;
		do {
			JsonObject part = BeeDesignerStore.operationDocument(world, record, "test:normalized_operation", offset);
			assertTrue(part.toString().getBytes(StandardCharsets.UTF_8).length <= BeeDesignerProtocol.MAX_BYTES);
			assertEquals(offset, part.get("offset").getAsInt());
			joined.append(part.get("text").getAsString());
			offset = part.get("next_offset").getAsInt();
			total = part.get("total").getAsInt();
			pieces++;
		} while (offset < total);
		assertTrue(pieces > 1);
		JsonObject restored = BeeDesignerProtocol.parse(joined.toString());
		assertEquals(normalized.get("definition"), restored.get("definition"));
		assertNotEquals(BeeDesignerDocument.fingerprint(staged), restored.get("baseline").getAsString());
		assertEquals(BeeDesignerDocument.fingerprint(normalized), restored.get("baseline").getAsString());
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerStore.operationDocument(world, record, "test:other", 0));
	}

	@Test
	void requestDigestKeepsReceivedSourceAndBaselineSeparateFromLaterNormalization() {
		JsonObject begin = JsonParser.parseString("{\"token\":\"" + TOKEN + "\",\"count\":1}").getAsJsonObject();
		BeeDesignerImportBatch batch = new BeeDesignerImportBatch(begin);
		JsonObject source = BeeDesignerDocument.create("test:request_digest");
		batch.add(source, null);
		String digest = batch.requestDigest();
		source.addProperty("later_client_edit", true);
		assertEquals(digest, batch.requestDigest());
		assertEquals("new", batch.baselines().get("test:request_digest"));
	}

	private static BeeDesignerOperation identity(JsonObject document, UUID token) {
		String id = document.get("id").getAsString();
		return BeeDesignerOperation.create(token, OWNER, BeeDesignerDocument.fingerprint(document), "import",
				Map.of(id, document.get("baseline").getAsString()), Map.of(id, document.toString()));
	}
}

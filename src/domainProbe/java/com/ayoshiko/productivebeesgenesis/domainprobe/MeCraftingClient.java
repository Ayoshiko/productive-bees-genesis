package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

final class MeCraftingClient {
	private static int previous=-1, step;
	private static MeTerminalRequest replay;
	static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
		if (previous!=stage) { previous=stage; step=0; }
		if (!(client.player.containerMenu instanceof NetworkCoreMenu menu)) return null;
		var session=menu.meTerminal(); var view=session.view();
		if (!MeBridgeProbe.ae() || !owner && stage==609) {
			if (step==0) { send(session.begin(BROWSE,-1,0,1,"")); step++; return null; }
			if (session.waiting()) return null; require(session.view().status()==Status.DISCONNECTED,"Unowned/absent ME accepted a request"); return ack();
		}
		if (!owner) return ack();
		if (stage==600 || stage==611) {
			if (client.screen instanceof NetworkTerminalScreen screen) {
				int left=screen.getGuiLeft(), top=screen.getGuiTop();
				screen.mouseClicked(left+16,top+16,0); screen.mouseReleased(left+16,top+16,0); return null;
			}
			if (!(client.screen instanceof MeTerminalScreen screen) || session.waiting() || view.mode()!=Mode.CATALOGUE || view.rows().isEmpty()) return null;
			if (stage==600) { require(view.more() && view.rows().size()==8,"Craftable pagination missing"); return ack(); }
			if (step==0) { input(screen,"filter","minecraft:iron_ingot"); input(screen,"quantity","2"); press(screen,"refresh"); step++; return null; }
			if (view.rows().size()!=1) return null;
			choose(screen,0); press(screen,"plan"); return ack();
		}
		if (stage==610 || stage==612) {
			if (client.screen instanceof MeTerminalScreen screen) { if (session.waiting()) return null; press(screen,"back"); return null; }
			return client.screen instanceof NetworkTerminalScreen ? ack() : null;
		}
		if (!(client.screen instanceof MeTerminalScreen screen) || session.waiting()) return null;
		if (stage==601 || stage==606) {
			if (step==0) { press(screen,"catalogue"); step++; return null; }
			if (step==1) { input(screen,"filter","minecraft:iron_ingot"); input(screen,"quantity",stage==601?"4":"2"); press(screen,"refresh"); step++; return null; }
			if (step==2) {
				if (view.mode()!=Mode.CATALOGUE || view.rows().size()!=1) return null;
				choose(screen,0); press(screen,"plan"); step++; return null;
			}
			if (view.mode()!=Mode.PLAN || view.status()==Status.WAITING) return null;
			require(view.confirm() && view.bytes()>0 && view.rows().stream().anyMatch(r->r.kind()==Kind.USED && r.amount()==(stage==601?8:4)),"Wrong ME material plan: "+view);
			if (stage==601) picture(client,"me-plan.png"); return ack();
		}
		if (stage==602) {
			if (step==0) { press(screen,"cpu"); step++; return null; }
			require(!view.cpu().isEmpty() && view.confirm(),"CPU selection failed"); return ack();
		}
		if (stage==603 || stage==607) {
			if (step==0) {
				long revision=view.revision(); int page=view.page(); press(screen,"confirm");
				replay=new MeTerminalRequest(menu.containerId,menu.terminalSession(),session.sequence(),CONFIRM,revision,-1,page,stage==603?4:2,"minecraft:iron_ingot"); step++; return null;
			}
			if (view.mode()!=Mode.TASKS || view.rows().isEmpty()) return null;
			require(view.rows().size()==1 && view.rows().getFirst().enabled(),"Missing cancellable AE2 job");
			if (stage==603) picture(client,"me-jobs.png"); return ack();
		}
		if (stage==604) { send(replay); return ack(); }
		if (stage==605) {
			if (step==0) { choose(screen,0); press(screen,"cancel"); step++; return null; }
			return view.status()==Status.CANCELLED || view.mode()==Mode.TASKS && view.rows().isEmpty() ? ack() : null;
		}
		if (stage==608) {
			if (step==0) { press(screen,"tasks"); step++; return null; }
			return view.mode()==Mode.TASKS && view.rows().isEmpty() ? ack() : null;
		}
		if (stage==609) {
			if (step==0) {
				var valid=session.begin(CONFIRM,-1,0,1,""); send(new MeTerminalRequest(valid.containerId(),valid.session(),valid.sequence(),CONFIRM,Long.MAX_VALUE,-1,0,1,"")); step++; return null;
			}
			require(view.status()==Status.STALE,"Forged plan revision accepted"); return ack();
		}
		return null;
	}
	private static void input(MeTerminalScreen screen,String key,String value) {
		var expected=Component.translatable("screen.productivebeesgenesis.me_terminal."+key);
		var box=screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).filter(e->e.getMessage().equals(expected)).findFirst().orElseThrow(); box.setValue(value);
	}
	private static void press(MeTerminalScreen screen,String key) {
		var expected=Component.translatable("screen.productivebeesgenesis.me_terminal."+key);
		var button=screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.getMessage().equals(expected)).findFirst().orElseThrow();
		require(button.active,"Disabled ME control: "+key); double x=button.getX()+button.getWidth()/2.0,y=button.getY()+7;
		screen.mouseClicked(x,y,0); screen.mouseReleased(x,y,0);
	}
	private static void choose(MeTerminalScreen screen,int row) { int left=(screen.width-Math.min(312,screen.width-8))/2,top=(screen.height-Math.min(236,screen.height-8))/2; screen.mouseClicked(left+45,top+60+row*16,0); }
	private static void send(MeTerminalRequest request) { require(request!=null,"ME request was still pending"); PacketDistributor.sendToServer(request); }
	private static void picture(Minecraft client,String name) throws Exception { Files.createDirectories(Path.of("results")); try(var image=Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results",name)); } }
	private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0,-1); }
}

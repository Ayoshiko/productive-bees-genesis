package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

/** 非阻塞打开的只读状态句柄；完整文件验证／新建落盘前不暴露权威对象。 */
public final class NetworkOpenHandle {
	public enum State { QUEUED, LOADING, CREATING, READY, RECOVERY, CANCELLED }
	private final Thread owner = Thread.currentThread();
	private final NetworkIdentity identity;
	final boolean creating;
	final long generation;
	private State state = State.QUEUED;
	private NetworkSavedData result;
	private String failure = "";
	NetworkOpenHandle(NetworkIdentity identity, boolean creating, long generation) {
		this.identity = java.util.Objects.requireNonNull(identity); this.creating = creating; this.generation = generation;
	}
	public NetworkIdentity identity() { return identity; }
	public State state() { check(); return state; }
	public String failure() { check(); return failure; }
	public boolean pending() { check(); return state == State.QUEUED || state == State.LOADING || state == State.CREATING; }
	public NetworkSavedData ready() {
		check(); if (state != State.READY) throw new IllegalStateException("Authority unavailable: " + state + " " + failure);
		return result;
	}
	public void cancel() { check(); if (pending()) { state = State.CANCELLED; result = null; } }
	void close() { check(); state = State.CANCELLED; result = null; }
	void state(State next) { check(); if (pending()) state = next; }
	void failure(String reason) { check(); failure = reason; }
	void reject(String reason) { check(); failure = reason; if (pending()) state = State.RECOVERY; }
	void complete(NetworkSavedData data) { check(); if (pending()) { result = data; failure = ""; state = State.READY; } }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Open handle belongs to the server thread"); }
}

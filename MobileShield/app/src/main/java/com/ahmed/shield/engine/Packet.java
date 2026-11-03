package com.ahmed.shield.engine;

/** Filled in by the native engine for every new connection. Field names are used by JNI. */
public class Packet {
    public long time;
    public int version;
    public int protocol;
    public String flags;
    public String saddr;
    public int sport;
    public String daddr;
    public int dport;
    public String data;
    public int uid;
    public boolean allowed;

    public Packet() {
    }
}

package com.ahmed.shield.engine;

/** One DNS answer (or TLS server name, then TTL is -1) seen by the native engine. */
public class ResourceRecord {
    public long Time;
    public String QName;
    public String AName;
    public String Resource;
    public int TTL;
    public int uid;

    public ResourceRecord() {
    }
}

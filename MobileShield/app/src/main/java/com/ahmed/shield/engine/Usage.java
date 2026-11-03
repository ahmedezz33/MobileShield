package com.ahmed.shield.engine;

/** Bytes sent and received by one closed connection. */
public class Usage {
    public long Time;
    public int Version;
    public int Protocol;
    public String DAddr;
    public int DPort;
    public int Uid;
    public long Sent;
    public long Received;

    public Usage() {
    }
}

package com.ahmed.shield.engine;

/** Returned to the native engine to let a connection through (optionally redirected). */
public class Allowed {
    public String raddr;
    public int rport;

    public Allowed() {
        this.raddr = null;
        this.rport = 0;
    }
}

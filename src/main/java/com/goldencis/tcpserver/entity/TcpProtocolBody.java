package com.goldencis.tcpserver.entity;

import lombok.Data;

/**
 * Created by limingchao on 2018/10/18.
 */
@Data
public class TcpProtocolBody {

    private String action;

    private String pipe;

    public TcpProtocolBody() {
    }

    public TcpProtocolBody(String action, String pipe) {
        this.action = action;
        this.pipe = pipe;
    }
}

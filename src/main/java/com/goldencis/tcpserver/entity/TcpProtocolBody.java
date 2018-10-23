package com.goldencis.tcpserver.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldencis.tcpserver.constants.ConstantsDto;
import lombok.Data;

import java.nio.ByteBuffer;

/**
 * Created by limingchao on 2018/10/18.
 */
@Data
public class TcpProtocolBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String action;

    private String pipe;

    public TcpProtocolBody() {
    }

    public TcpProtocolBody(String action, String pipe) {
        this.action = action;
        this.pipe = pipe;
    }

    public static void response(String action, String pipe, ByteBuffer buffer) throws JsonProcessingException {
        //写入校验标示符
        buffer.putInt(ConstantsDto.TCP_PROTOCOL_VERIFY);
        //写入版本信息
        buffer.putInt(ConstantsDto.TCP_PROTOCOL_VERSION);

        //构建响应消息体
        TcpProtocolBody responseBody = new TcpProtocolBody(action, pipe);
        byte[] bytes = MAPPER.writeValueAsString(responseBody).getBytes();
        //写入消息体长度
        buffer.putInt(bytes.length + ConstantsDto.TCP_PROTOCOL_HEAD_SIZE);
        //写入消息体字节数组
        buffer.put(bytes);
    }
}

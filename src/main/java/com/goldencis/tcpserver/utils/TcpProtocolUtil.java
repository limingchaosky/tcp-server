package com.goldencis.tcpserver.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldencis.tcpserver.constants.ConstantsDto;
import com.goldencis.tcpserver.entity.TcpProtocolBody;
import com.goldencis.tcpserver.runner.TcpTransferRunner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;

/**
 * 解析自定义tcp协议的工具类
 * Created by limingchao on 2018/10/24.
 */
public class TcpProtocolUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, TcpTransferRunner> runnerMap;

    private static Map<SocketChannel, String> vncTargetUuidMap;

    private static Map<SocketChannel, String> noVNCUuidMap;

    /**
     * 解析目标通道发送的tcp协议数据
     * 依次解析协议头(校验位(固定)、版本号、协议长度),协议体
     * @param channel 管道
     * @throws IOException
     */
    public static TcpProtocolBody parseTcpProtocol(SocketChannel channel) throws IOException {
        //生成读取字节序的缓冲区
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        TcpProtocolBody tcpBody = null;

        //设置接受字节序的字节数组
        Integer size;
        byte[] body;
        boolean parsehead = false;
        int offset = 0;

        while (channel.read(buffer) > 0) {
            buffer.flip();
            if (!parsehead && buffer.remaining() > ConstantsDto.TCP_PROTOCOL_HEAD_SIZE) {

                //获取校验位
                int verify = buffer.getInt();
                if (!ConstantsDto.TCP_PROTOCOL_VERIFY.equals(verify)) {
                    throw new IOException("校验位不匹配!");
                }

                //获取版本号
                int version = buffer.getInt();
                if (!ConstantsDto.TCP_PROTOCOL_VERSION.equals(version)) {
                    throw new IOException("版本不匹配!");
                }

                //获取消息体大小
                size = buffer.getInt() - ConstantsDto.TCP_PROTOCOL_HEAD_SIZE;
                body = new byte[size];

                parsehead = true;
            } else {
                //抛出异常。
                throw new IOException("协议格式错误");
            }

            //本次包中，剩余未读字节数组长度
            int remaining = buffer.remaining();
            if (offset + remaining < size) {//偏移量加剩余量 小于 协议体大小时，说明还有数据在下一次缓冲区中
                //分包的情况，将本次包中的数据，读入字节数组中的指定位置。
                buffer.get(body, offset, offset + remaining);
            } else {
                //将数据全部读入字节数组中
                buffer.get(body);
            }
            //偏移量增加读取字节数组长度
            offset += remaining;

            //完全读取完后，再做协议体的解析
            if (offset == size) {
                //转化协议体
                tcpBody = MAPPER.readValue(new String(body), TcpProtocolBody.class);

                //匹配协议动作
                if (ConstantsDto.CONNECT_VNC_CLIENT.equals(tcpBody.getAction())) {//匹配握手动作
                    //与vnc服务进行握手
                    handshakeWithVNCTarget(channel, tcpBody, buffer);
                } else if (ConstantsDto.CONNECT_NOVNC_SERVER.equals(tcpBody.getAction())) {
                    //与noVNC的websocket进行握手
                    handshakeWithNoVNC(channel, tcpBody);
                }
            }
            //清除当前buffer。
            buffer.clear();
        }
        return tcpBody;
    }

    /**
     * 根据协议体中的管道id，匹配两端管道，响应消息体，开启TCP转发线程
     * @param destinationChannel 目标管道
     * @param tcpBody tcp协议体
     * @param buffer 缓冲区
     * @throws IOException IO异常
     */
    private static void handshakeWithVNCTarget(SocketChannel destinationChannel, TcpProtocolBody tcpBody, ByteBuffer buffer) throws IOException {
        //获取管道唯一标示
        String uuid = tcpBody.getPipe();

        //以SocketChannel为key，uuid为value，方便channle查询自身的uuid
        vncTargetUuidMap.put(destinationChannel, uuid);

        //获取runner
        TcpTransferRunner tcpTransferRunner = runnerMap.get(uuid);
        //设置目标通道
        tcpTransferRunner.setDestinationChannel(destinationChannel);
        //设置目标通道状态
        tcpTransferRunner.setDestinationReady(true);

        //获取本次tcp转发的选择器
        Selector redirectSelector = tcpTransferRunner.getSelector();
        //将目标通道注册进单独的转发选择器
        destinationChannel.register(redirectSelector, SelectionKey.OP_READ);

        //切换非阻塞模式
        destinationChannel.configureBlocking(false);

        //完成协议解析，清空buffer，
        buffer.clear();

        //将返回的tcp协议体信息写入buffer中
        TcpProtocolBody.response(ConstantsDto.CONNECT_VNC_SUCCESS, uuid, buffer);

        //切换到读模式
        buffer.flip();
        //写回通道
        destinationChannel.write(buffer);

        //启动TCP转发线程
        new Thread(tcpTransferRunner).start();
    }

    private static void handshakeWithNoVNC(SocketChannel channel, TcpProtocolBody tcpBody) {
        //获取管道唯一标示
        String uuid = tcpBody.getPipe();

        //以SocketChannel为key，uuid为value，方便channle查询自身的uuid
        noVNCUuidMap.put(channel, uuid);
    }

    public static void setRunnerMap(Map<String, TcpTransferRunner> runnerMap) {
        TcpProtocolUtil.runnerMap = runnerMap;
    }

    public static void setVncTargetUuidMap(Map<SocketChannel, String> vncTargetUuidMap) {
        TcpProtocolUtil.vncTargetUuidMap = vncTargetUuidMap;
    }

    public static void setNoVNCUuidMap(Map<SocketChannel, String> noVNCUuidMap) {
        TcpProtocolUtil.noVNCUuidMap = noVNCUuidMap;
    }
}

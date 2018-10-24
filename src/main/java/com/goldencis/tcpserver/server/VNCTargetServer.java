package com.goldencis.tcpserver.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goldencis.tcpserver.constants.ConstantsDto;
import com.goldencis.tcpserver.entity.TcpProtocolBody;
import com.goldencis.tcpserver.runner.TcpTransferRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 用于监听VNC目标端连接请求的服务，开辟单独的端口.
 * 连接成功后，需要先按照格式解析自定义的TCP协议。
 * 解析协议头正常后，解析协议体。
 * 根据协议体中的动作标示，可以执行进行握手操作，关联noVNC端和目标端，传递数据。
 * Created by limingchao on 2018/10/18.
 */
@Component
public class VNCTargetServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private Map<String, TcpTransferRunner> runnerMap;

    private Map<SocketChannel, String> uuidMap = new HashMap<>();

    public VNCTargetServer(Map<String, TcpTransferRunner> runnerMap) {
        this.runnerMap = runnerMap;
    }

    @Value(value = "${server.VNCtarget.port}")
    private Integer vNCtargetPort;

    @Autowired
    private ThreadPoolTaskExecutor taskExecutor;

    public void clear() {
        try {
            for (TcpTransferRunner tcpTransferRunner : runnerMap.values()) {
                tcpTransferRunner.close();
            }
            for (SocketChannel channel : uuidMap.keySet()) {
                if (channel != null) {
                    channel.close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        runnerMap.clear();
        uuidMap.clear();
    }

    public void server() throws IOException, InterruptedException {
        //1. 获取通道
        ServerSocketChannel ssChannel = ServerSocketChannel.open();

        //2. 切换非阻塞模式
        ssChannel.configureBlocking(false);

        //3. 绑定连接
        ssChannel.bind(new InetSocketAddress(vNCtargetPort));

        //4. 获取选择器
        Selector selector = Selector.open();

        //5. 将通道注册到选择器上, 并且指定“监听接收事件”
        ssChannel.register(selector, SelectionKey.OP_ACCEPT);


        //6. 轮询式的获取选择器上已经“准备就绪”的事件
        while (selector.select() > 0) {

            //7. 获取当前选择器中所有注册的“选择键(已就绪的监听事件)”
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();

            while (it.hasNext()) {
                //8. 获取准备“就绪”的是事件
                SelectionKey sk = it.next();

                try {
                    //9. 判断具体是什么事件准备就绪
                    if (sk.isAcceptable()) {
                        //10. 若“接收就绪”，获取客户端连接
                        SocketChannel sChannel = ssChannel.accept();

                        //11. 切换非阻塞模式
                        sChannel.configureBlocking(false);

                        //12. 将该通道注册到选择器上
                        sChannel.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ);


                    } else if (sk.isReadable()) {
                        if (sk.channel() instanceof SocketChannel) {
                            SocketChannel destinationChannel = null;
                            try {
                                destinationChannel = (SocketChannel) sk.channel();
                                if (!uuidMap.containsKey(destinationChannel)) {
                                    //解析目标通道发送的tcp协议数据
                                    this.parseTcpProtocol(destinationChannel);
                                }
                            } catch (Exception e) {
                                //log
                                //关闭当前请求的流资源
                                try {
                                    //取消注册
                                    if (sk != null) {
                                        sk.cancel();
                                    }
                                    //取消选择键 SelectionKey
                                    if (it != null) {
                                        it.remove();
                                    }
                                    //关闭资源
                                    this.close(destinationChannel);
                                    continue;
                                } catch (IOException ex) {
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (sk != null) {
                        if (sk.channel() != null) {
                            //关闭资源
                            sk.channel().close();
                        }
                        //取消注册
                        sk.cancel();
                    }
                }
                //取消选择键 SelectionKey
                it.remove();
            }
        }
    }

    /**
     * 关闭资源的方法
     * 需要关闭当前通道，如果存在guid，需要关闭对应的noVNC端的通道
     * @param destinationChannel
     * @throws IOException
     */
    private void close(SocketChannel destinationChannel) throws IOException {
        if (destinationChannel != null) {
            if (uuidMap.containsKey(destinationChannel)) {
                String uuid = uuidMap.get(destinationChannel);
                //关闭关联的noVNC端通道
                runnerMap.get(uuid).close();
            }
            //关闭目标通道
            destinationChannel.close();
        }
    }

    /**
     * 解析目标通道发送的tcp协议数据
     * 依次解析协议头(校验位(固定)、版本号、协议长度),协议体
     * @param destinationChannel
     * @throws IOException
     */
    private void parseTcpProtocol(SocketChannel destinationChannel) throws IOException {
        //生成读取字节序的缓冲区
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        //设置接受字节序的字节数组
        Integer size;
        byte[] body;
        boolean parsehead = false;
        int offset = 0;

        while (destinationChannel.read(buffer) > 0) {
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
                TcpProtocolBody tcpBody = MAPPER.readValue(new String(body), TcpProtocolBody.class);

                //匹配协议动作
                if (ConstantsDto.CONNECT_VNC_CLIENT.equals(tcpBody.getAction())) {//匹配握手动作
                    //进行握手
                    this.handshake(destinationChannel, tcpBody, buffer);
                }
            }
            //清除当前buffer。
            buffer.clear();
        }
    }

    /**
     * 根据协议体中的管道id，匹配两端管道，响应消息体，开启TCP转发线程
     * @param destinationChannel 目标管道
     * @param tcpBody tcp协议体
     * @param buffer 缓冲区
     * @throws IOException IO异常
     */
    private void handshake(SocketChannel destinationChannel, TcpProtocolBody tcpBody, ByteBuffer buffer) throws IOException {
        //获取管道唯一标示
        String uuid = tcpBody.getPipe();

        //以SocketChannel为key，uuid为value，方便channle查询自身的uuid
        uuidMap.put(destinationChannel, uuid);

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

    public Map<String, TcpTransferRunner> getRunnerMap() {
        return runnerMap;
    }

    public Map<SocketChannel, String> getUuidMap() {
        return uuidMap;
    }
}

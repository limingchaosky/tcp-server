package com.goldencis.tcpserver.server;

import com.goldencis.tcpserver.runner.TcpTransferRunner;
import com.goldencis.tcpserver.utils.TcpProtocolUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
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

    private ServerSocketChannel ssChannel;

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
            if (ssChannel != null) {
                ssChannel.close();
                ssChannel = null;
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
                        sChannel.register(selector, SelectionKey.OP_READ);


                    } else if (sk.isReadable()) {
                        if (sk.channel() instanceof SocketChannel) {
                            SocketChannel destinationChannel = null;
                            try {
                                destinationChannel = (SocketChannel) sk.channel();
                                if (!uuidMap.containsKey(destinationChannel)) {
                                    //解析目标通道发送的tcp协议数据
                                    TcpProtocolUtil.parseTcpProtocol(destinationChannel);
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

    public Map<SocketChannel, String> getUuidMap() {
        return uuidMap;
    }
}

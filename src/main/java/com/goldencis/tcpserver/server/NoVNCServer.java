package com.goldencis.tcpserver.server;

import com.goldencis.tcpserver.runner.TcpTransferRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 用于监听NoVNC的web端连接请求的服务，开辟单独的端口.
 * 一旦noVNC连接成功，进入可写状态，便为其创建runner实例.
 * 之后，通知对应的客户端连接。
 * 最后，将该noVNC通道从服务的监听列表中移除。
 * Created by limingchao on 2018/10/18.
 */
@Component
public class NoVNCServer {

    private Map<String, TcpTransferRunner> runnerMap;

    private Map<SocketChannel, String> uuidMap = new HashMap<>();

    public NoVNCServer(Map<String, TcpTransferRunner> runnerMap) {
        this.runnerMap = runnerMap;
    }

    @Value(value = "${server.noVNC.port}")
    private Integer noVNCPort;

    public void server() throws IOException, InterruptedException {
        //1. 获取通道
        ServerSocketChannel ssChannel = ServerSocketChannel.open();

        //2. 切换非阻塞模式
        ssChannel.configureBlocking(false);

        //3. 绑定连接
        ssChannel.bind(new InetSocketAddress(noVNCPort));

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

                //9. 判断具体是什么事件准备就绪
                if (sk.isAcceptable()) {
                    //10. 若“接收就绪”，获取客户端连接
                    SocketChannel sChannel = ssChannel.accept();

                    //11. 切换非阻塞模式
                    sChannel.configureBlocking(false);

                    //12. 将该通道注册到选择器上
                    sChannel.register(selector, SelectionKey.OP_WRITE);
                } else if (sk.isWritable()) {
                    if (sk.channel() instanceof SocketChannel) {
                        //为对应的noVNC通道创建runner实例
                        this.createTcpTransferRunner((SocketChannel) sk.channel());
                        //需要通知客户端


                        //将该通道从监听列表中移除
                        sk.cancel();
                    }
                }
                //15. 取消选择键 SelectionKey
                it.remove();
            }
        }
    }

    private void createTcpTransferRunner(SocketChannel channel) throws IOException {
        if (!uuidMap.containsKey(channel)) {
            //生成任务uuid
            String uuid = UUID.randomUUID().toString();

            //为本次tcp转发新建选择器
            Selector redirectSelector = Selector.open();
            //将源通道注册进单独的转发选择器
            channel.register(redirectSelector, SelectionKey.OP_READ);

            //切换非阻塞模式
            channel.configureBlocking(false);

            TcpTransferRunner tcpTransferRunner = new TcpTransferRunner(uuid, redirectSelector, true, channel);

            //以uuid为key，TCPTransferRunner为value，等待符合条件时，
            runnerMap.put(uuid, tcpTransferRunner);
            //以SocketChannel为key，uuid为value，方便channle查询自身的uuid
            uuidMap.put(channel, uuid);
        }
    }
}

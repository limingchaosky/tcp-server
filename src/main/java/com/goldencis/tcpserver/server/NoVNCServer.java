package com.goldencis.tcpserver.server;

import com.goldencis.tcpserver.constants.ConstantsDto;
import com.goldencis.tcpserver.mq.MQClient;
import com.goldencis.tcpserver.runner.TcpTransferRunner;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
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

    @Autowired
    private Map<String, TcpTransferRunner> runnerMap;

    @Autowired
    private MQClient publisher;

    private Map<SocketChannel, String> uuidMap = new HashMap<>();

    public NoVNCServer(Map<String, TcpTransferRunner> runnerMap) {
        this.runnerMap = runnerMap;
    }

    @Value(value = "${server.noVNC.port}")
    private Integer noVNCPort;

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
                        SocketChannel socketChannel = (SocketChannel) sk.channel();
                        //为对应的noVNC通道创建runner实例
                        this.createTcpTransferRunner(socketChannel);
                        //需要通知客户端
                        this.notifyVNCTarget(socketChannel);

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

    /**
     *
     * @param socketChannel
     */
    private void notifyVNCTarget(SocketChannel socketChannel) {
        String clientUserIdsStr = "";

        //设置消息为审批消息
        String message = "bdpapprove";

        //设置type为持久消息
        int type = MQClient.MSG_SERIALIZABLE;

        //组装消息内容
        JSONObject contentJson = new JSONObject();
        contentJson.put("action", ConstantsDto.CONNECT_VNC_SERVER);
        contentJson.put("pipe", uuidMap.get(socketChannel));

        //使用消息缓存服务
        publisher.clientNotify(clientUserIdsStr ,message, contentJson.toString(), type);
    }

    public Map<String, TcpTransferRunner> getRunnerMap() {
        return runnerMap;
    }

    public Map<SocketChannel, String> getUuidMap() {
        return uuidMap;
    }
}

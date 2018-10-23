package com.goldencis.tcpserver.runner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * tcp通道数据转发器，监听器上只有源通道和目标通道。
 * 监听其可读状态。将进入该状态的通道内数据，写入另外一个通道中去。
 * Created by limingchao on 2018/10/18.
 */
public class TcpTransferRunner implements Runnable {

    private String uuid;

    private boolean on;

    private Selector selector;

    private boolean sourceReady;

    private SocketChannel sourceChannel;

    private boolean destinationReady;

    private SocketChannel destinationChannel;

    //读取字节序的缓冲区
    private ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024 * 4);

    public TcpTransferRunner() {
    }

    public TcpTransferRunner(String uuid, Selector selector, boolean sourceReady, SocketChannel sourceChannel) {
        this.uuid = uuid;
        this.selector = selector;
        this.sourceReady = sourceReady;
        this.sourceChannel = sourceChannel;
    }

    @Override
    public void run() {
        //轮询式的获取选择器上已经“准备就绪”的事件
        try {
            while (selector.select() > 0) {

                //获取当前选择器中所有注册的“选择键(已就绪的监听事件)”
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();

                while (it.hasNext()) {
                    //获取准备“就绪”的是事件
                    SelectionKey sk = it.next();

                    //9. 判断具体是什么事件准备就绪
                    if (sk.isReadable()) {
                        if (sk.channel() instanceof SocketChannel) {
                            if (sk.channel().equals(sourceChannel)) {
                                this.transferBuffer(sourceChannel, destinationChannel);
                            } else {
                                this.transferBuffer(destinationChannel, sourceChannel);
                            }
                        }
                    }

                    //取消选择键 SelectionKey
                    it.remove();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            //关闭转发线程资源
            this.close();
        }
    }

    public void close() {
        if (sourceChannel != null) {
            try {
                sourceChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (destinationChannel != null) {
            try {
                destinationChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void transferBuffer(SocketChannel sourceChannel, SocketChannel destinationChannel) throws IOException {
        //数据转发
        while (sourceChannel.read(buffer) > 0) {
            buffer.flip();
            //注意SocketChannel.write()方法的调用是在一个while循环中的。
            //Write()方法无法保证能写多少字节到SocketChannel。所以，我们重复调用write()直到Buffer没有要写的字节为止。
            while (buffer.hasRemaining()) {
                destinationChannel.write(buffer);
            }
            buffer.clear();
        }
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public boolean isSourceReady() {
        return sourceReady;
    }

    public void setSourceReady(boolean sourceReady) {
        this.sourceReady = sourceReady;
    }

    public SocketChannel getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(SocketChannel sourceChannel) {
        this.sourceChannel = sourceChannel;
    }

    public boolean isDestinationReady() {
        return destinationReady;
    }

    public void setDestinationReady(boolean destinationReady) {
        this.destinationReady = destinationReady;
    }

    public SocketChannel getDestinationChannel() {
        return destinationChannel;
    }

    public void setDestinationChannel(SocketChannel destinationChannel) {
        this.destinationChannel = destinationChannel;
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean on) {
        this.on = on;
    }

    public Selector getSelector() {
        return selector;
    }

    public void setSelector(Selector selector) {
        this.selector = selector;
    }
}

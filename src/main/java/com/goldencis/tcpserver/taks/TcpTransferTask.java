package com.goldencis.tcpserver.taks;

import com.goldencis.tcpserver.server.NoVNCServer;
import com.goldencis.tcpserver.server.VNCTargetServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;

/**
 * Created by limingchao on 2018/10/23.
 */
@Component
public class TcpTransferTask {

    @Autowired
    private ThreadPoolTaskExecutor taskExecutor;

    @Autowired
    private VNCTargetServer vncTargetServer;

    @Autowired
    private NoVNCServer noVNCServer;

    @PostConstruct
    public void init() {
        taskExecutor.execute(() -> {
            while (true) {
                try {
                    noVNCServer.server();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    noVNCServer.clear();
                }
            }
        });

        taskExecutor.execute(() -> {
            while (true) {
                try {
                    vncTargetServer.server();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    vncTargetServer.clear();
                }
            }
        });
    }
}

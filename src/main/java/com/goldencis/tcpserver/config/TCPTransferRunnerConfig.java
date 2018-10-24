package com.goldencis.tcpserver.config;

import com.goldencis.tcpserver.runner.TcpTransferRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by limingchao on 2018/10/23.
 */
@Configuration
public class TCPTransferRunnerConfig {

    @Bean
    public Map<String, TcpTransferRunner> runnerMap() {
        return new HashMap<>();
    }

}

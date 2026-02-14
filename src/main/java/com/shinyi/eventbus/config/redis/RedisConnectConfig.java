package com.shinyi.eventbus.config.redis;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis connection configuration
 * @author MSGA
 */
@Slf4j
@NoArgsConstructor
@Data
public class RedisConnectConfig {

    /**
     * Whether this is the default Redis connection
     */
    private Boolean isDefault = Boolean.FALSE;

    /**
     * Redis host
     */
    private String host = "localhost";

    /**
     * Redis port
     */
    private int port = 6379;

    /**
     * Redis password
     */
    private String password;

    /**
     * Database index
     */
    private int database = 0;

    /**
     * Connection timeout in milliseconds
     */
    private int connectionTimeout = 2000;

    /**
     * Socket timeout in milliseconds
     */
    private int socketTimeout = 2000;

    /**
     * Redis channel prefix for pub/sub
     */
    private String channelPrefix = "eventbus:";

    /**
     * Consumer group name for stream
     */
    private String group;

    /**
     * Stream key (if using stream instead of pub/sub)
     */
    private String streamKey;

    /**
     * Use Redis Stream instead of Pub/Sub
     */
    private boolean useStream = false;

    /**
     * Maximum number of reconnect attempts
     */
    private int maxAttempts = 3;
}

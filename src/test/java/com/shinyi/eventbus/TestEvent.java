package com.shinyi.eventbus;


import lombok.Data;

import java.io.Serializable;

@Data
public class TestEvent implements Serializable {

    public static final String TOPIC_NAME = "TEST_EVENT";

    private String fieldTest;
    private String fieldTest2;

}

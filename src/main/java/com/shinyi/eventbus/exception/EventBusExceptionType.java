package com.shinyi.eventbus.exception;


/**
 * 异常错误码定义
 * @author MSGA
 */
public enum EventBusExceptionType implements ErrorCode {

    /**
     * 异常
     */
    LISTENER_UNKNOWN_ERROR( "200000","事件监听器未知异常"),
    LISTENER_BIZ_ERROR("200001","事件监听器业务异常 <ERR>"),
    EVENTBUS_DRIVER_ERROR( "200002","事件监听器驱动异常"),
    EVENTBUS_QUEUE_ERROR("200003","异步线程池队列已满"),
    EVENTBUS_PUBLISH_ERROR("200004","事件发布异常"),
    EVENTBUS_PUBLISH_EVENT_NULL_ERROR("200005","发布事件不能为空"),
    EVENTBUS_DRIVER_DISABLE_ERROR( "200006","<EL> 事件监听器驱动已禁用"),
    EVENTBUS_DRIVER_NOT_FOUND_ERROR("200007", "没有找到对应事件驱动器，请检查是否注册该驱动到事件中心 <EL>"),
    EVENTBUS_DRIVER_INIT_ERROR("200008", "事件驱动器初始化异常 <EL>");

    private final String code;

    private final String description;

    EventBusExceptionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.code;
    }

    @Override
    public String getDescription() {
        return this.description;
    }
}

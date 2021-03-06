package cn.ayl.socket.rpc;

import cn.ayl.common.enumeration.RequestType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;

/**
 * created by Rock-Ayl 2019-12-10
 * 一个Context对应一个物理的数据链路(就是一个当前的会话)
 */
public class Context {

    //请求类型
    public RequestType requestType = RequestType.none;
    //请求者ip
    public String ip;
    //UriPath
    public String uriPath;
    //ChannelId
    public ChannelId channelId;
    //Channel
    public Channel channel;

    //用户信息
    public User user = new User();

    /**
     * 用户信息
     */
    public class User {
        //用户的cookieId
        public String cookieId = null;
        //用户id
        public long userId;
    }

    //受保护的,不允许new,但允许继承
    protected Context() {
    }

    /**
     * 初始化context
     *
     * @param type
     * @param channel
     * @return
     */
    public static Context createInitContext(RequestType type, Channel channel) {
        Context context = new Context();
        context.requestType = type;
        context.channelId = channel.id();
        context.channel = channel;
        context.ip = channel.remoteAddress().toString();
        return context;
    }

}

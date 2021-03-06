package cn.ayl.util;

import cn.ayl.common.entry.ParamEntry;
import cn.ayl.common.enumeration.ContentType;
import cn.ayl.common.json.JsonObject;
import cn.ayl.common.json.JsonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryAttribute;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created By Rock-Ayl on 2020-05-18
 * http请求工具类,包括http请求的取参
 */
public class HttpUtils {

    protected static Logger logger = LoggerFactory.getLogger(HttpUtils.class);

    /**
     * 判断一个请求是否为https即拥有SSL
     *
     * @param ctx
     * @return
     */
    public static boolean isHttps(ChannelHandlerContext ctx) {
        if (ctx.pipeline().get(SslHandler.class) != null) {
            return true;
        }
        return false;
    }

    /**
     * 从请求中获取参数
     *
     * @param httpRequest get/post请求
     * @param paramMap    所需参数及参数对象类型,可以为null
     * @return
     */
    public static Map<String, Object> getParams(HttpRequest httpRequest, LinkedHashMap<String, ParamEntry> paramMap) {
        //初始化
        Map<String, Object> params = null;
        //如果是get
        if (httpRequest.method() == HttpMethod.GET) {
            //取get参数的同时过滤不必须的参数
            params = HttpUtils.getParamsFromGet(httpRequest, paramMap);
        } else if (httpRequest.method() == HttpMethod.POST) {
            //取post参数的同时过滤不必须的参数
            params = HttpUtils.getParamsFromPost(httpRequest, paramMap);
        }
        return params;
    }

    /**
     * 从get请求中获取参数(过滤掉不需要的参数)
     *
     * @param httpRequest get请求
     * @param paramMap    所需的参数组及class类型,可以为null
     * @return
     */
    private static Map<String, Object> getParamsFromGet(HttpRequest httpRequest, LinkedHashMap<String, ParamEntry> paramMap) {
        //参数组
        Map<String, Object> params = new HashMap<>();
        //如果请求为GET继续
        if (httpRequest.method() == HttpMethod.GET) {
            //获取请求uri
            String uri = httpRequest.uri();
            //将Uri分割成path、参数组
            QueryStringDecoder decoder = new QueryStringDecoder(uri);
            //获取参数组
            Map<String, List<String>> paramList = decoder.parameters();
            //循环
            for (Map.Entry<String, List<String>> entry : paramList.entrySet()) {
                //判空
                if (paramMap != null) {
                    //如果该参数是我需要的
                    if (paramMap.containsKey(entry.getKey())) {
                        //强转并组装
                        params.put(entry.getKey(), TypeUtils.castObject(paramMap.get(entry.getKey()).clazz, entry.getValue().get(0)));
                    }
                } else {
                    //直接组装
                    params.put(entry.getKey(), entry.getValue().get(0));
                }
            }
        }
        return params;
    }

    /**
     * 从post请求中获取参数(过滤掉不需要的参数)
     *
     * @param httpRequest post请求
     * @param paramMap    所需的参数组及class类型,可以为null
     * @return
     */
    private static Map<String, Object> getParamsFromPost(HttpRequest httpRequest, LinkedHashMap<String, ParamEntry> paramMap) {
        //初始化餐数据
        Map<String, Object> params = new HashMap<>();
        //如果请求为POST
        if (httpRequest.method() == HttpMethod.POST) {
            //处理POST请求
            ContentType contentType = ContentType.parse(httpRequest.headers().get("Content-Type"));
            //根据内容类型获取参数
            switch (contentType) {
                //application/x-www-form-urlencoded
                case XWWWFormUrlencoded:
                    params = getFormDefaultParamsFromPost(httpRequest, paramMap);
                    break;
                //application/json
                case Json:
                    params = getJsonParamsFromPost(httpRequest, paramMap);
                    break;
            }
            return params;
        }
        return params;
    }

    /**
     * 解析 application/x-www-form-urlencoded 参数(过滤掉不需要的参数)
     *
     * @param httpRequest post请求
     * @param paramMap    所需的参数组及class类型,可以为null
     * @return
     */
    private static Map<String, Object> getFormDefaultParamsFromPost(HttpRequest httpRequest, LinkedHashMap<String, ParamEntry> paramMap) {
        //返回值初始化
        Map<String, Object> params = new HashMap<>();
        //构建请求解码器
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), httpRequest);
        //获取data
        List<InterfaceHttpData> postData = decoder.getBodyHttpDatas();
        //循环
        for (InterfaceHttpData data : postData) {
            if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                MemoryAttribute attribute = (MemoryAttribute) data;
                //获取key
                String key = attribute.getName();
                //判空
                if (paramMap != null) {
                    //如果是所需参数
                    if (paramMap.containsKey(key)) {
                        //强转并组装
                        params.put(key, TypeUtils.castObject(paramMap.get(key).clazz, attribute.getValue()));
                    }
                } else {
                    //直接组装
                    params.put(key, attribute.getValue());
                }
            }
        }
        return params;
    }

    /**
     * 解析 application/json 参数(过滤掉不需要的参数)
     *
     * @param httpRequest post请求
     * @param paramMap    所需参数及对应的class类型,可以为null
     * @return
     */
    private static Map<String, Object> getJsonParamsFromPost(HttpRequest httpRequest, LinkedHashMap<String, ParamEntry> paramMap) {
        //初始化参数对象
        Map<String, Object> params = new HashMap<>();
        //强转下请求
        FullHttpRequest fullReq = (FullHttpRequest) httpRequest;
        //获取请求内容
        ByteBuf content = fullReq.content();
        //初始化内容byte[]
        byte[] reqContent = new byte[content.readableBytes()];
        //写入byte[]
        content.readBytes(reqContent);
        //读取成字符并转化为Json
        JsonObject jsonParams;
        try {
            jsonParams = JsonUtil.parse(new String(reqContent, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            //报错
            logger.error("解析byte[]为String出现异常:[{}]", e);
            //返回
            return params;
        }
        //循环
        for (String key : jsonParams.keySet()) {
            //判空
            if (paramMap != null) {
                //如果是所需要的参数
                if (paramMap.containsKey(key)) {
                    //强转并组装
                    params.put(key, TypeUtils.castObject(paramMap.get(key).clazz, jsonParams.get(key)));
                }
            } else {
                //直接组装
                params.put(key, jsonParams.get(key));
            }
        }
        //返回
        return params;
    }

}

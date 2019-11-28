package cn.ayl.socket.handler;

import cn.ayl.config.Const;
import cn.ayl.entry.MethodEntry;
import cn.ayl.entry.ParamEntry;
import cn.ayl.entry.RegistryEntry;
import cn.ayl.entry.ServiceEntry;
import cn.ayl.util.ScanClassUtil;
import cn.ayl.util.StringUtil;
import cn.ayl.util.TypeUtil;
import cn.ayl.util.json.JsonObject;
import cn.ayl.util.json.JsonUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryAttribute;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;

import static cn.ayl.config.Const.Json_Error_Param;
import static cn.ayl.config.Const.Json_No_Service;
import static cn.ayl.config.Const.RequestType.*;

/**
 * created by Rock-Ayl on 2019-11-18
 * http请求处理器
 */
public class HttpHandler extends ChannelInboundHandlerAdapter {

    protected static Logger logger = LoggerFactory.getLogger(HttpHandler.class);

    UploadFileHandler uploadFileHandler;
    ResourceHandler responseHandler;

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("通道不活跃的");
        super.channelInactive(ctx);
        if (uploadFileHandler != null) {
            uploadFileHandler.clear();
        }
    }

    /**
     * 通道，请求过来从这里分类
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //处理Http请求的分别处理
        try {
            //处理service和upload请求的基础处理
            if (msg instanceof HttpRequest) {
                final HttpRequest req = (HttpRequest) msg;
                handleHttpRequest(ctx, req);
            } else if (msg instanceof HttpContent && uploadFileHandler != null) {
                //处理接受来的upload文件块
                uploadFileHandler.handleHttpContent(ctx, (HttpContent) msg);
            } else if (msg instanceof HttpContent) {
                //没有upload处理器的文件块
                ResponseHandler.sendMessage(ctx, HttpResponseStatus.OK, "失败的上传请求.");
            } else {
                //没有upload处理器的文件块
                ResponseHandler.sendMessage(ctx, HttpResponseStatus.OK, "失败的请求.");
            }
        } catch (Exception e) {
            logger.error("channelRead", e);
        } finally {
            //释放
            ReferenceCountUtil.safeRelease(msg);
        }
    }

    /**
     * 每个channel都有一个唯一的id值
     * asLongText方法是channel的id的全名
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        //todo 连接打开时
        logger.info(ctx.channel().localAddress().toString() + " ,handlerAdded！, channelId=" + ctx.channel().id().asLongText());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        //todo 连接关闭时
        logger.info(ctx.channel().localAddress().toString() + " ,handlerRemoved！, channelId=" + ctx.channel().id().asLongText());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        //todo 出现异常
        logger.error("Client:" + ctx.channel().remoteAddress() + " ,error", cause.getMessage());
        ctx.close();
    }

    /**
     * 根据path和params处理业务并返回结果
     *
     * @param path eg:  /Organize/login
     * @param req
     * @return
     */
    private Object handleServiceFactory(String path, HttpRequest req) {
        //根据请求路径获得服务和方法名
        List<String> serviceAndMethod = getServiceAndMethod(path);
        if (serviceAndMethod.size() < 2) {
            return Json_No_Service;
        }
        //获取服务
        ServiceEntry serviceEntry = RegistryEntry.serviceMap.get(serviceAndMethod.get(0));
        //如果服务存在
        if (serviceEntry == null) {
            return Json_No_Service;
        }
        //获取服务中的方法
        MethodEntry methodEntry = serviceEntry.methodMap.get(serviceAndMethod.get(1));
        //如果方法存在
        if (methodEntry == null) {
            return Const.Json_No_InterFace;
        }
        //获取方法中的参数组
        LinkedHashMap<String, ParamEntry> paramMap = methodEntry.paramMap;
        List<String> paramList = methodEntry.paramList;
        //根据获取请求参数
        Map<String, Object> params = getParamsFromChannelByService(req, paramMap);
        if (params == null) {
            return Json_Error_Param;
        }
        //根据List遍历处理请求
        for (String paramKey : paramList) {
            //是否非必须该参数 false:必须 true:不必须
            Boolean optional = paramMap.get(paramKey).optional;
            //是否存在该值
            Boolean hasParam = params.containsKey(paramKey);
            //如果必须传并且参数中没有对应Key,回手掏
            if (optional == false && hasParam == false) {
                return Json_Error_Param;
            }
        }
        //已确认服务接口参数均对应上,获取服务的实现类
        Class serviceClass = ScanClassUtil.findImplClass(serviceEntry.interFaceClass);
        //是否存在实现
        if (serviceClass == null) {
            return Const.Json_No_Impl;
        }
        try {
            //调用构造函数
            Constructor noArgConstructor = serviceClass.getDeclaredConstructor();
            //实现类
            Object service = noArgConstructor.newInstance();
            //组装参数和参数类型
            Object[] valueArr = new Object[paramList.size()];
            Class<?>[] valueTypeArr = new Class[paramList.size()];
            for (int i = 0; i < paramList.size(); i++) {
                //参数类型
                Class<?> parType = methodEntry.paramMap.get(paramList.get(i)).clazz;
                String key = paramList.get(i);
                //组装参数
                valueArr[i] = params.get(key);
                //组装类型
                valueTypeArr[i] = parType;

            }
            //定位服务的方法
            Method method = serviceClass.getMethod(methodEntry.name, valueTypeArr);
            logger.info("service:[{}] Method:[{}]", serviceClass, methodEntry.name);
            //加入参数并执行
            Object resultObject = method.invoke(service, valueArr);
            //获取返回值
            return resultObject;
        } catch (Exception e) {
            logger.error("请求构建函数失败, error: [{}]", e);
            return Const.Json_Find_Exception;
        }
    }

    /***
     * 根据路径开头分配Http请求内容
     *
     * upload开头是上传
     * htmlPage开头是请求静态资源
     * 如果有文件后缀是资源
     * 什么都不是，默认看做服务
     * @param path
     * @return
     */
    private Const.RequestType getHttpRequestType(String path) {
        if (path.startsWith(Const.UploadPath)) {
            return upload;
        } else if (path.startsWith(Const.HttpPagePath)) {
            return htmlPage;
        } else if (!StringUtil.isEmpty(FilenameUtils.getExtension(path))) {
            return resource;
        } else {
            return service;
        }
    }

    /**
     * 分配各类http请求内容处理
     *
     * @param ctx
     * @param req
     * @throws Exception
     */
    private void handleHttpRequest(final ChannelHandlerContext ctx, HttpRequest req) throws Exception {
        //获得请求path
        String path = getPath(req);
        //根据请求路径分配是哪一种请求
        Const.RequestType type = getHttpRequestType(path);
        switch (type) {
            //请求静态资源
            case resource:
                //创建一个静态资源处理器
                responseHandler = new ResourceHandler();
                //处理请求
                responseHandler.handleResource(ctx, req);
                break;
            //请求页面
            case htmlPage:
                //todo
                break;
            //上传请求
            case upload:
                if (uploadFileHandler != null) {
                    uploadFileHandler.clear();
                }
                //创建一个上传请求处理器
                uploadFileHandler = new UploadFileHandler();
                //处理请求
                uploadFileHandler.handleRequest(ctx, req);
                break;
            //服务请求
            case service:
            default:
                handleService(ctx, req, path);
                break;
        }
    }

    /**
     * 处理http服务请求
     *
     * @param ctx
     * @param req  请求
     * @param path 请求路径
     */
    private void handleService(ChannelHandlerContext ctx, HttpRequest req, String path) {
        FullHttpResponse response;
        //根据请求类型处理请求 get post ...
        if (req.method() == HttpMethod.GET || req.method() == HttpMethod.POST) {
            //根据path和params处理业务并返回结果
            Object result = handleServiceFactory(path, req);
            //组装结果并响应请求
            response = ResponseHandler.getResponseOKAndJson(HttpResponseStatus.OK, result);
        } else {
            //todo 处理其他请求类型的请求 eg: OPTIONS HEAD DELETE 等等
            response = ResponseHandler.getResponseOKAndText(HttpResponseStatus.INTERNAL_SERVER_ERROR, "ok");
        }
        // 发送响应并关闭连接
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 根据 get、post 类型和已知参数组从请求中获取参数
     *
     * @param req
     * @return
     */
    public Map<String, Object> getParamsFromChannelByService(HttpRequest req, LinkedHashMap<String, ParamEntry> paramMap) {
        Map<String, Object> params = null;
        if (req.method() == HttpMethod.GET) {
            //获取get请求的参数
            params = getGetParamsFromChannel(req, paramMap);
        } else if (req.method() == HttpMethod.POST) {
            //获取post请求的参数
            params = getPostParamsFromChannel(req, paramMap);
        }
        return params;
    }

    /**
     * 根据请求路径获得服务和方法名
     *
     * @param path
     * @return
     */
    private List<String> getServiceAndMethod(String path) {
        List<String> result = new ArrayList<>();
        //服务名
        String serviceName = null;
        //方法名
        String methodName = null;
        //去掉第一个/
        path = path.substring(1);
        //拆分
        String[] piecewise = path.split("/");
        //分别组装服务名和方法名
        if (piecewise.length >= 2) {
            for (String info : piecewise) {
                if (serviceName == null) {
                    serviceName = info;
                } else {
                    if (methodName == null) {
                        methodName = info;
                        break;
                    }
                }
            }
        }
        //组装
        result.add(serviceName);
        result.add(methodName);
        return result;
    }

    /**
     * Http获取请求Path
     *
     * @param req
     * @return
     */
    public String getPath(HttpRequest req) {
        String path = null;
        try {
            path = new URI(req.getUri()).getPath();
        } catch (Exception e) {
            logger.error("接口解析错误.");
        } finally {
            return path;
        }
    }

    /**
     * Http获取GET方式传递的参数
     *
     * @param fullHttpRequest
     * @return
     */
    private Map<String, Object> getGetParamsFromChannel(HttpRequest fullHttpRequest, LinkedHashMap<String, ParamEntry> paramMap) {
        //参数组
        Map<String, Object> params = new HashMap<>();
        //如果请求为GET继续
        if (fullHttpRequest.method() == HttpMethod.GET) {
            // 处理get请求
            QueryStringDecoder decoder = new QueryStringDecoder(fullHttpRequest.uri());
            Map<String, List<String>> paramList = decoder.parameters();
            for (Map.Entry<String, List<String>> entry : paramList.entrySet()) {
                //如果该参数是我需要的
                if (paramMap.containsKey(entry.getKey())) {
                    //强转并组装
                    params.put(entry.getKey(), TypeUtil.castObject(paramMap.get(entry.getKey()).clazz, entry.getValue().get(0)));
                }
            }
            return params;
        } else {
            return null;
        }
    }

    /**
     * 根据已知参数 从POST方式的Http请求 获取传递的参数
     *
     * @param fullHttpRequest
     * @return
     */
    private Map<String, Object> getPostParamsFromChannel(HttpRequest fullHttpRequest, LinkedHashMap<String, ParamEntry> paramMap) {
        //参数组
        Map<String, Object> params;
        //如果请求为POST
        if (fullHttpRequest.method() == HttpMethod.POST) {
            // 处理POST请求
            String strContentType = fullHttpRequest.headers().get("Content-Type").trim();
            //从from中获取
            if (strContentType.contains("x-www-form-urlencoded")) {
                params = getFormParams(fullHttpRequest, paramMap);
            } else if (strContentType.contains("application/json")) {
                try {
                    //从body中获取
                    params = getJSONParams(fullHttpRequest, paramMap);
                } catch (UnsupportedEncodingException e) {
                    logger.error("从body中获取参数失败");
                    return null;
                }
            } else {
                return null;
            }
            return params;
        } else {
            return null;
        }
    }

    /**
     * 根据已知参数组从Http解析from表单数据（Content-Type = x-www-form-urlencoded）
     *
     * @param fullHttpRequest
     * @return
     */
    private Map<String, Object> getFormParams(HttpRequest fullHttpRequest, LinkedHashMap<String, ParamEntry> paramMap) {
        Map<String, Object> params = new HashMap<>();
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), fullHttpRequest);
        List<InterfaceHttpData> postData = decoder.getBodyHttpDatas();
        for (InterfaceHttpData data : postData) {
            if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                MemoryAttribute attribute = (MemoryAttribute) data;
                String attributeName = attribute.getName();
                //如果是所需参数
                if (paramMap.containsKey(attributeName)) {
                    //强转并组装
                    params.put(attributeName, TypeUtil.castObject(paramMap.get(attributeName).clazz, attribute.getValue()));
                }
            }
        }
        return params;
    }

    /**
     * Http解析json数据（Content-Type = application/json）
     *
     * @param httpRequest
     * @return
     * @throws UnsupportedEncodingException
     */
    private Map<String, Object> getJSONParams(HttpRequest httpRequest, LinkedHashMap<String, ParamEntry> paramMap) throws UnsupportedEncodingException {
        FullHttpRequest fullReq = (FullHttpRequest) httpRequest;
        Map<String, Object> params = new HashMap<>();
        ByteBuf content = fullReq.content();
        byte[] reqContent = new byte[content.readableBytes()];
        content.readBytes(reqContent);
        String strContent = new String(reqContent, "UTF-8");
        JsonObject jsonParams = JsonUtil.parse(strContent);
        for (Object key : jsonParams.keySet()) {
            //如果是所需参数
            if (paramMap.containsKey(key.toString())) {
                //强转并组装
                params.put(key.toString(), TypeUtil.castObject(paramMap.get(key.toString()).clazz, jsonParams.get((String) key)));
            }
        }
        return params;
    }

}
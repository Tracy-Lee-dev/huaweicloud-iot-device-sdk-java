package com.huaweicloud.sdk.iot.device;


import com.huaweicloud.sdk.iot.device.client.ClientConf;
import com.huaweicloud.sdk.iot.device.client.requests.DeviceMessage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;


/**
 * 一个传输字符串数据的tcp server，客户端建链后，首条消息是鉴权消息，携带设备标识nodeId。server将收到的消息通过gateway转发给平台
 */
public class StringTcpServer {

    public static SimpleGateway simpleGateway;
    private static Logger log = Logger.getLogger(StringTcpServer.class);
    private int port;

    public StringTcpServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        int port;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        } else {
            port = 8080;
        }

        ClientConf clientConf = new ClientConf();
        clientConf.setServerUri("ssl://iot-acc.cn-north-4.myhuaweicloud.com:8883");
        clientConf.setDeviceId("5e06bfee334dd4f33759f5b3_demo");
        clientConf.setSecret("mysecret");

        simpleGateway = new SimpleGateway(new SubDevicesFilePersistence(), clientConf);
        if (simpleGateway.init() != 0) {
            return;
        }
        Logger.getLogger("io.netty").setLevel(Level.INFO);
        new StringTcpServer(port).run();

    }

    public void run() throws Exception {

        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {

                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast("decoder", new StringDecoder());
                            ch.pipeline().addLast("encoder", new StringEncoder());
                            ch.pipeline().addLast("handler", new StringHandler());

                            log.info("initChannel:" + ch.remoteAddress());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            log.info("tcp server start......");

            ChannelFuture f = b.bind(port).sync();
            f.channel().closeFuture().sync();

        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();

            log.info("tcp server close");
        }
    }

    public class StringHandler extends SimpleChannelInboundHandler<String> {


        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String s) throws Exception {
            Channel incoming = ctx.channel();
            log.info("channelRead0" + incoming.remoteAddress() + " msg :" + s);

            //如果是首条消息,创建session
            Session session = simpleGateway.getSessionByChannel(incoming.id().asLongText());
            if (session == null) {
                String nodeId = s;
                session = simpleGateway.createSession(nodeId, incoming);

                //创建会话失败，拒绝连接
                if (session == null) {
                    log.info("close channel");
                    ctx.close();
                }
            } else {

                //如果需要上报属性则调用reportSubDeviceProperties
                DeviceMessage deviceMessage = new DeviceMessage(s);
                deviceMessage.setDeviceId(session.getDeviceId());
                simpleGateway.publishSubDeviceMessage(deviceMessage, null);

            }

        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel incoming = ctx.channel();
            log.info("exceptionCaught:" + incoming.remoteAddress());
            // 当出现异常就关闭连接
            log.error(cause);
            ctx.close();
            simpleGateway.removeSession(incoming.id().asLongText());
        }
    }

}

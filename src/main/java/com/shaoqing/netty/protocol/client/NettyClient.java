package com.shaoqing.netty.protocol.client;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.shaoqing.netty.protocol.NettyConstant;
import com.shaoqing.netty.protocol.codec.NettyMessageDecoder;
import com.shaoqing.netty.protocol.codec.NettyMessageEncoder;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;

/**
 * Netty客户端
 * @author lsq 
 */
public class NettyClient {

	private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
	EventLoopGroup group = new NioEventLoopGroup();
	
	public void connect(int port, String host) throws Exception{
		//配置客户端NIO线程组
		try {
			Bootstrap b = new Bootstrap();
			b.group(group)
				.channel(NioSocketChannel.class)
				.option(ChannelOption.TCP_NODELAY, true)
				.handler(new ChannelInitializer<SocketChannel>(){
					@Override
					protected void initChannel(SocketChannel ch) throws Exception {
						ch.pipeline().addLast(
							new NettyMessageDecoder(1024 * 1024, 4, 4));
						ch.pipeline().addLast("MessageEncoder",
							new NettyMessageEncoder());
					    ch.pipeline().addLast("readTimeoutHandler",
						    new ReadTimeoutHandler(50));
					    ch.pipeline().addLast("LoginAuthHandler",
						    new LoginAuthReqHandler());		
					    ch.pipeline().addLast("HeartBeatHandler",
					    	new HeartBeatReqHandler());
					}
					
				});
			//发起异步连接
			ChannelFuture future = b.connect(
				new InetSocketAddress(host, port),
				new InetSocketAddress(NettyConstant.LOCALIP, NettyConstant.LOCAL_PORT)
			).sync();
			future.channel().closeFuture().sync();
		} finally {
			//所有资源释放完成后，清空资源，再次发起重连操作
			executor.execute(new Runnable(){
				@Override
				public void run() {					
					try {
						TimeUnit.SECONDS.sleep(1);
						//发起重连操作
						connect(NettyConstant.PORT, NettyConstant.REMOTEIP);
					} catch (Exception e) {				
						e.printStackTrace();
					}			
				}
				
			});
		}
	}
	
	public static void main(String args[]) throws Exception{
		new NettyClient().connect(NettyConstant.PORT, NettyConstant.REMOTEIP);
	}
}

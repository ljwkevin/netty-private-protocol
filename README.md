前言：《Netty权威指南》这本书让我收益巨大，接下来将结合自己的理解以及书中私有协议栈开发的例子，谈谈私有协议开发的思路，可能会遇到的问题，以及解决方法。同时会谈谈针对网络攻击（例如syn攻击）的解决方案。重点谈谈一些设计理念上的东西。

（一）通信模型：
![这里写图片描述](http://img.blog.csdn.net/20170627140731118?watermark/2/text/aHR0cDovL2Jsb2cuY3Nkbi5uZXQvS2lsbHVhWm9sZHljaw==/font/5a6L5L2T/fontsize/400/fill/I0JBQkFCMA==/dissolve/70/gravity/SouthEast)
具体如下：
（1）握手请求时候发送请求消息，同时携带节点等信息。
（2）服务端对握手请求消息进行校验（包括白名单校验，重复登录校验等）。通过后，返回握手应答消息
（3）客户端发送业务消息
（4）服务端发送心跳消息
（5）客户端发送心跳消息
（6）服务端发送业务消息
（7）由于是全双工模式，两边都需要关闭连接

（二）设计消息结构
该协议栈消息分为：（1）消息头 （2）消息体
消息头定义如下（Header）

| 名字 | 类型 | 长度 | 描述
| --- | --- | --- | --- |
| crcCode   |整型 int	|32	| 校验和 |
| length   |整型 int	|32	|消息长度，整个消息，包括消息头和消息体 |
| sessionId|长整型long|	64|	集群节点内全局唯一，由会话ID生成器生成 |
| type|	Byte | 8 |	0: 表示请求消息 1: 业务响应消息 2: 业务ONE WAY消息(即是请求又是响应消息) 3: 握手请求消息 4: 握手应答消息 5: 心跳请求消息 6: 心跳应答消息 | 
| priority|Byte | 8|	消息优先级 |
| attchment |Map| |	附件 |
PS：这里可以再加上一些其他的字段，例如，公钥，压缩方式等。这些留着以后进行优化。

消息体定义则较为简单，就是一个Object对象。


（三）安全性设计理念
目前使用的是IP白名单策略，如果是白名单的IP则校验通过，否则就拒绝对方连接。但是，更为可靠的方式应该是基于加密体系的，同时采用ssl连接等方式。

（四）可靠性设计理念
网络环境是及其险恶的，我们可能会遇到各种各样的网络问题，例如连接超时，闪断，对方进程僵死或者处理及其缓慢。为了使我们设计出来的协议在异常情景下能继续工作，我们需要在可靠性下点功夫。

（1）心跳监测机制
因为网络的不可靠性, 有可能在 TCP 保持长连接的过程中, 由于某些突发情况, 例如网络闪断, 突然掉电等, 会造成服务器和客户端的连接中断. 在这些突发情况下, 如果恰好服务器和客户端之间没有交互的话, 那么它们是不能在短时间内发现对方已经掉线的. 为了解决这个问题, 我们就需要引入心跳机制。在网络空闲时候采取心跳机制来检测网络的互通性。一旦网络出现问题，则关闭链路后尝试重连。

在这里我们可以这样设计：
1，在服务端和客户端都添加一个ReadTimeoutHandler,同时设定时间2，客户端实现一个HearBeatReqHandler来发送心跳包，而服务端实现一个HeartBeatRespHandler来接受心跳包同时返回心跳应答包
3，如果网络出现问题，则在ReadTimeoutHandler设定的时间内没有读到任何包，则进行尝试重连，尝试重连也可以制定次数，看自己的需求决定。

（2）重连机制
如果网络中断，那么我们可以在等待一个特定时间后进行尝试重连，为了有足够的时间让服务端释放资源句柄，这点非常重要，否则将会报错。在重连时候，可以将问题记录到错误日志，方便以后定位和修改错误。大致代码如下：

```
finally {
	System.out.println("start reconnecting");
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
```

（3）重复登录保护
如果客户端一直重复登录而不进行制止，那么将会导致句柄资源被耗尽（网络攻击也会导致这一点，将会本文最后进行说明）。因此我们在握手阶段，不仅要进行白名单校验，同时也要检测是否已经登录过。是的话则拒绝重复登录，同时记录下来。但是为了使断线客户端能重连成功，在关闭链路后需要清空该客户端的缓存信息。

（4）消息缓存重发
在链路中断恢复之前，缓存在消息队列中待发送的消息不能丢失，等链路恢复后，重发消息。同时注意消息缓存队列的上限不宜过大。但是这种方式不太好。应该提供一个通知机制，将发送失败的消息通知给业务层，由业务层来决定该丢弃还是重发。

（五）可扩展性
每个协议都是要跟随业务而改进的，所以一定要有一定的扩展能力，在这里我们在消息头中定义来可选附件attachment字段，用户可以进行自定义扩展。
在商用的协议下，我们可能还需要统一的消息拦截，接口日志，安全加解密等可以方便增加删除的字段。


（六）协议开发过程中可能遇到的问题以及解决方案

（1）TCP粘包/拆包问题：
如果我们熟悉TCP编程，那么就会知道TCP底层的粘包／拆包机制。

产生的原因有三个：
1，应用程序write写入的字节大小大于套接口发送缓冲区大小
2，进行MSS大小的tcp分段
3，以太网帧的payload大于MTU进行IP分片

解决方法：
（1）消息定长，例如规定每个报文的大小为固定长度，不足补空位
（2）在包尾增加回车换行符进行分割。例如FTP
（3）将消息分为消息头和消息体，头中包含消息长度。

在这里，我们可以通过Netty的LengthFieldBasedFrameDecoder解码器，它支持自动的TCP粘包和半包处理，只需要给出标识消息长度的字段偏移量和消息长度自身所占的字节数，就能自动实现半包处理。关于LengthFieldBasedFrameDecoder，建议看看源码或者API，就知道它的机制了。


（2）TCP flood攻击
这个问题没有遇到，只是在我开发时候自己想到的，于是这里也简单谈一下
产生的原因：
（1）攻击方在收到syn+ack包后丢弃
（2）服务端半连接队列越来越长，光是遍历和保存就会占据大量资源，何况同时还需要对这个列表进行syn+ack包发送
（3）此时将会出现拒绝服务等情况

解决方案：
（1）缩短syn_timeout时间，通过缩短收到syn报文到确定这个报文无效后的时间，可以提高系统的负荷，但是可能会降低正常用户的体验
（2）设置syn_cookie，就是给每一个请求连接的IP地址分配一个Cookie，如果短时间内连续受到某个IP的重复SYN报文，就认定是受到了攻击，以后从这个IP地址来的包会被丢弃
（3）牺牲系统服务资源来获取更大的等待队列长度
（4）设置防火墙，代替服务端进行三次握手，只有当握手成功，才转发给服务器。这种一般就要找一些厂商付费了。



参考资源：
《Netty权威编程》



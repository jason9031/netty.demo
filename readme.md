# A Netty For Http And WebSocket Demo

use:

netty 4

jdk 1.8

remark:

================

2019-11-13日 更新，已将启动整合至Server上

如何使用？

启动Server,然后调用Post请求:

http://127.0.0.1:8888/Organize/login

body:

{
	name:"Rock-Ayl",
	pwd:123456,
	isRole:false
}

看intf中的Organize接口和其实现的OrganizeService

照着这个class和方法继续写就可以各种实现请求和返回Json的业务了。

这是一个超轻量的伪·微服务架构系统，因为目前只有单机，很多地方都没有完善。

@author是新人，照着我的老大架构仿照着去写的项目，纯粹用来练手。

P`S:习惯真是很可怕的事情。

================
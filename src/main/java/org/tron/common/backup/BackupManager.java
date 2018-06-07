package org.tron.common.backup;

import static org.tron.common.backup.BackupManager.BackupStatusEnum.INIT;
import static org.tron.common.backup.BackupManager.BackupStatusEnum.MASTER;
import static org.tron.common.backup.BackupManager.BackupStatusEnum.SLAVER;
import static org.tron.common.net.udp.message.UdpMessageTypeEnum.BACKUP_KEEP_ALIVE;

import io.netty.util.internal.ConcurrentSet;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.net.udp.handler.EventHandler;
import org.tron.common.net.udp.handler.MessageHandler;
import org.tron.common.net.udp.handler.UdpEvent;
import org.tron.common.net.udp.message.Message;
import org.tron.common.net.udp.message.backup.KeepAliveMessage;
import org.tron.common.net.udp.message.discover.FindNodeMessage;
import org.tron.common.net.udp.message.discover.NeighborsMessage;
import org.tron.common.net.udp.message.discover.PingMessage;
import org.tron.common.net.udp.message.discover.PongMessage;
import org.tron.common.overlay.discover.node.Node;
import org.tron.common.overlay.discover.node.NodeHandler;
import org.tron.core.config.args.Args;

@Component
public class BackupManager implements EventHandler{

  private static final Logger logger = LoggerFactory.getLogger("BackupManager");

  private Args args = Args.getInstance();

  private int priority = args.getBackupPriority();

  private int port = args.getBackupPort();

  private Set<String> members = new ConcurrentSet<>();

  private ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

  private MessageHandler messageHandler;

  private volatile BackupStatusEnum status = MASTER;

  private volatile long lastKeepAliveTime;

  private volatile long keepAliveTimeout = 3000;

  public void setMessageHandler(MessageHandler messageHandler) {
    this.messageHandler = messageHandler;
  }

  public enum BackupStatusEnum{
    INIT,
    SLAVER,
    MASTER
  }

  public void setStatus(BackupStatusEnum status) {
    logger.info("Change backup status to {}", status);
    this.status = status;
  }

  public BackupStatusEnum getStatus() {
    return status;
  }

  public void init() {

    for (String member : args.getBackupMembers()) {
      members.add(member);
    }

    logger.info("Backup members : size= {}, {}", members.size(), members);

    setStatus(INIT);

    lastKeepAliveTime = System.currentTimeMillis();

    executorService.scheduleWithFixedDelay(() -> {
      try {
        if (!status.equals(MASTER) && System.currentTimeMillis() - lastKeepAliveTime > keepAliveTimeout){
          if (status.equals(SLAVER)){
            setStatus(INIT);
            lastKeepAliveTime = System.currentTimeMillis();
          }else {
            setStatus(MASTER);
          }
        }
        if (status.equals(SLAVER)){
          return;
        }
        members.forEach(member -> messageHandler.accept(new UdpEvent(new KeepAliveMessage(status.equals(MASTER), priority),
            new InetSocketAddress(member, port))));
      } catch (Throwable t) {
        logger.error("Exception in send keep alive message", t);
      }
    }, 1, 1, TimeUnit.SECONDS);
  }

  @Override
  public void handleEvent(UdpEvent udpEvent) {
    InetSocketAddress sender = udpEvent.getAddress();
    Message msg = udpEvent.getMessage();
    if (!msg.getType().equals(BACKUP_KEEP_ALIVE)){
      logger.warn("Receive not keep alive message from {}, type {}", sender.getHostString(), msg.getType());
      return;
    }
    if (!members.contains(sender.getHostString())){
      logger.warn("Receive keep alive message from {} is not my member.", sender.getHostString());
      return;
    }

    lastKeepAliveTime = System.currentTimeMillis();

    KeepAliveMessage keepAliveMessage = (KeepAliveMessage) msg;

    if (status.equals(MASTER)){
      if (keepAliveMessage.getFlag() && keepAliveMessage.getPriority() > priority){
        setStatus(SLAVER);
        return;
      }
    }

    if (status.equals(INIT)){
      if (keepAliveMessage.getFlag() || keepAliveMessage.getPriority() > priority){
        setStatus(SLAVER);
        return;
      }
    }
  }

  @Override
  public void channelActivated(){
    init();
  }


}

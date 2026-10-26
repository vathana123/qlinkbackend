package com.qlink.features.push;
import com.google.firebase.*;
import com.google.firebase.messaging.*;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.concurrent.*;
@Component
public class FirebasePushGateway implements PushGateway {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(FirebasePushGateway.class);
 private final FirebaseApp app;
 public FirebasePushGateway(@Value("${qlink.push.enabled:false}") boolean enabled,@Value("${qlink.push.project-id:}") String project) throws java.io.IOException {
  if(!enabled) {app=null;log.warn("Push disabled: PUSH_ENABLED=false; chat messages will not enqueue notifications");return;}
  if(project.isBlank()) throw new IllegalStateException("Set FIREBASE_PROJECT_ID before enabling push");
  try {
   app=FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault()).setProjectId(project).build(),"qlink-push");
  } catch(java.io.IOException | RuntimeException ex) {
   log.error("Firebase Admin initialization failed project={} failureType={}; check Application Default Credentials",project,ex.getClass().getSimpleName());
   throw ex;
  }
  log.info("Firebase Admin initialized for project={}; FCM authorization is checked on dispatch",project);
 }
 @Override public boolean enabled() {return app!=null;}
 @Override public void send(PushDevice device,UUID room,UUID message,UUID recipient,UUID notification) throws Exception {
  if(app==null) throw new IllegalStateException("Push is disabled");
  var payload=Message.builder().setToken(device.getToken())
   .setNotification(Notification.builder().setTitle("qLink").setBody("New message. Open qLink to view it.").build())
   .putData("roomId",room.toString()).putData("messageId",message.toString()).putData("recipientId",recipient.toString()).putData("notificationId",notification.toString())
   .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).setCollapseKey(room.toString())
    .setNotification(AndroidNotification.builder().setTag(message.toString()).setClickAction("FLUTTER_NOTIFICATION_CLICK").build()).build())
   .setApnsConfig(ApnsConfig.builder().putHeader("apns-collapse-id",message.toString()).setAps(Aps.builder().setSound("default").build()).build()).build();
  try {FirebaseMessaging.getInstance(app).sendAsync(payload).get(10,TimeUnit.SECONDS);}
  catch(ExecutionException ex) {
   if(ex.getCause() instanceof FirebaseMessagingException f) log.warn("FCM rejected notification={} code={}",notification,f.getMessagingErrorCode());
   if(ex.getCause() instanceof FirebaseMessagingException f && f.getMessagingErrorCode()==MessagingErrorCode.UNREGISTERED) throw new InvalidToken();
   throw ex;
  }
 }
 @jakarta.annotation.PreDestroy public void close(){if(app!=null) app.delete();}
}

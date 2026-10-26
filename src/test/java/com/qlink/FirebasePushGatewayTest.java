package com.qlink;

import com.google.api.core.ApiFutures;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.api.client.json.gson.GsonFactory;
import com.google.gson.JsonParser;
import com.qlink.features.push.FirebasePushGateway;
import com.qlink.features.push.PushDevice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirebasePushGatewayTest {
 @Test void disabledPushDoesNotRequestCredentials() throws Exception {
  try(var credentials=mockStatic(GoogleCredentials.class)) {
   var gateway=new FirebasePushGateway(false,"");
   assertThat(gateway.enabled()).isFalse();credentials.verifyNoInteractions();gateway.close();
  }
 }
 @Test void dispatchUsesConfiguredProjectAndGenericNotificationWithRoutingData() throws Exception {
  var app=mock(FirebaseApp.class);var messaging=mock(FirebaseMessaging.class);
  var credentials=mock(GoogleCredentials.class);
  try(var google=mockStatic(GoogleCredentials.class);var apps=mockStatic(FirebaseApp.class);var fcm=mockStatic(FirebaseMessaging.class)) {
   google.when(GoogleCredentials::getApplicationDefault).thenReturn(credentials);
   apps.when(()->FirebaseApp.initializeApp(any(FirebaseOptions.class),eq("qlink-push"))).thenReturn(app);
   fcm.when(()->FirebaseMessaging.getInstance(app)).thenReturn(messaging);
   when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFuture("accepted"));
   var gateway=new FirebasePushGateway(true,"qlink-b1257");
   var options=ArgumentCaptor.forClass(FirebaseOptions.class);
   apps.verify(()->FirebaseApp.initializeApp(options.capture(),eq("qlink-push")));
   assertThat(options.getValue().getProjectId()).isEqualTo("qlink-b1257");
   var device=new PushDevice();device.setToken("test-token-not-a-real-credential");
   var room=UUID.randomUUID();var message=UUID.randomUUID();var recipient=UUID.randomUUID();var notification=UUID.randomUUID();
   gateway.send(device,room,message,recipient,notification);
   var payload=ArgumentCaptor.forClass(Message.class);verify(messaging).sendAsync(payload.capture());
   var json=JsonParser.parseString(GsonFactory.getDefaultInstance().toString(payload.getValue())).getAsJsonObject();
   assertThat(json.getAsJsonObject("notification").get("body").getAsString()).isEqualTo("New message. Open qLink to view it.");
   var data=json.getAsJsonObject("data");
   assertThat(data.get("roomId").getAsString()).isEqualTo(room.toString());
   assertThat(data.get("messageId").getAsString()).isEqualTo(message.toString());
   assertThat(data.get("recipientId").getAsString()).isEqualTo(recipient.toString());
   assertThat(data.get("notificationId").getAsString()).isEqualTo(notification.toString());
   var android=json.getAsJsonObject("android").getAsJsonObject("notification");
   assertThat(android.get("tag").getAsString()).isEqualTo(message.toString());
   assertThat(android.get("click_action").getAsString()).isEqualTo("FLUTTER_NOTIFICATION_CLICK");
   gateway.close();verify(app).delete();
  }
 }
}

/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.util.Pair;

import org.thoughtcrime.securesms.crypto.DecryptingQueue;
import org.thoughtcrime.securesms.crypto.KeyExchangeProcessor;
import org.thoughtcrime.securesms.crypto.MasterSecretUtil;
import org.thoughtcrime.securesms.crypto.TextSecureCipher;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.EncryptingSmsDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.protocol.WirePrefix;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientFactory;
import org.thoughtcrime.securesms.recipients.RecipientFormattingException;
import org.thoughtcrime.securesms.recipients.Recipients;
import org.thoughtcrime.securesms.sms.IncomingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingKeyExchangeMessage;
import org.thoughtcrime.securesms.sms.IncomingPreKeyBundleMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.sms.MultipartSmsMessageHandler;
import org.thoughtcrime.securesms.sms.OutgoingKeyExchangeMessage;
import org.thoughtcrime.securesms.sms.SmsTransportDetails;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libaxolotl.DuplicateMessageException;
import org.whispersystems.libaxolotl.InvalidKeyException;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.InvalidVersionException;
import org.whispersystems.libaxolotl.LegacyMessageException;
import org.whispersystems.libaxolotl.NoSessionException;
import org.whispersystems.libaxolotl.StaleKeyExchangeException;
import org.whispersystems.libaxolotl.UntrustedIdentityException;
import org.whispersystems.libaxolotl.protocol.KeyExchangeMessage;
import org.whispersystems.libaxolotl.protocol.PreKeyWhisperMessage;
import org.whispersystems.libaxolotl.protocol.WhisperMessage;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.libaxolotl.InvalidKeyIdException;
import org.whispersystems.textsecure.storage.RecipientDevice;
import org.whispersystems.textsecure.util.Base64;

import java.io.IOException;
import java.util.List;

public class SmsReceiver {

  private MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();

  private final Context context;

  public SmsReceiver(Context context) {
    this.context = context;
  }

  private IncomingTextMessage assembleMessageFragments(List<IncomingTextMessage> messages) {
    IncomingTextMessage message = new IncomingTextMessage(messages);

    if (WirePrefix.isEncryptedMessage(message.getMessageBody()) ||
        WirePrefix.isKeyExchange(message.getMessageBody())      ||
        WirePrefix.isPreKeyBundle(message.getMessageBody())     ||
        WirePrefix.isEndSession(message.getMessageBody()))
    {
      return multipartMessageHandler.processPotentialMultipartMessage(message);
    } else {
      return message;
    }
  }

  private Pair<Long, Long> storeSecureMessage(MasterSecret masterSecret, IncomingTextMessage message) {
    Pair<Long, Long> messageAndThreadId = DatabaseFactory.getEncryptingSmsDatabase(context)
                                                         .insertMessageInbox(masterSecret, message);

    if (masterSecret != null) {
      DecryptingQueue.scheduleDecryption(context, masterSecret, messageAndThreadId.first,
                                         messageAndThreadId.second,
                                         message.getSender(), message.getSenderDeviceId(),
                                         message.getMessageBody(), message.isSecureMessage(),
                                         message.isKeyExchange(), message.isEndSession());
    }

    return messageAndThreadId;
  }

  private Pair<Long, Long> storeStandardMessage(MasterSecret masterSecret, IncomingTextMessage message) {
    EncryptingSmsDatabase encryptingDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
    SmsDatabase           plaintextDatabase  = DatabaseFactory.getSmsDatabase(context);

    if (masterSecret != null) {
      return encryptingDatabase.insertMessageInbox(masterSecret, message);
    } else if (MasterSecretUtil.hasAsymmericMasterSecret(context)) {
      return encryptingDatabase.insertMessageInbox(MasterSecretUtil.getAsymmetricMasterSecret(context, null), message);
    } else {
      return plaintextDatabase.insertMessageInbox(message);
    }
  }

  private Pair<Long, Long> storePreKeyWhisperMessage(MasterSecret masterSecret,
                                                     IncomingPreKeyBundleMessage message)
  {
    Log.w("SmsReceiver", "Processing prekey message...");
    EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(context);

    if (masterSecret != null) {
      try {
        Recipient            recipient            = RecipientFactory.getRecipientsFromString(context, message.getSender(), false).getPrimaryRecipient();
        RecipientDevice      recipientDevice      = new RecipientDevice(recipient.getRecipientId(), message.getSenderDeviceId());
        SmsTransportDetails  transportDetails     = new SmsTransportDetails();
        TextSecureCipher     cipher               = new TextSecureCipher(context, masterSecret, recipientDevice, transportDetails);
        byte[]               rawMessage           = transportDetails.getDecodedMessage(message.getMessageBody().getBytes());
        PreKeyWhisperMessage preKeyWhisperMessage = new PreKeyWhisperMessage(rawMessage);
        byte[]               plaintext            = cipher.decrypt(preKeyWhisperMessage);

        IncomingEncryptedMessage bundledMessage     = new IncomingEncryptedMessage(message, new String(transportDetails.getEncodedMessage(preKeyWhisperMessage.getWhisperMessage().serialize())));
        Pair<Long, Long>         messageAndThreadId = database.insertMessageInbox(masterSecret, bundledMessage);

        database.updateMessageBody(masterSecret, messageAndThreadId.first, new String(plaintext));

        Intent intent = new Intent(KeyExchangeProcessor.SECURITY_UPDATE_EVENT);
        intent.putExtra("thread_id", messageAndThreadId.second);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent, KeyCachingService.KEY_PERMISSION);

        return messageAndThreadId;
      } catch (InvalidKeyException | RecipientFormattingException | InvalidMessageException | IOException | NoSessionException e) {
        Log.w("SmsReceiver", e);
        message.setCorrupted(true);
      } catch (InvalidVersionException e) {
        Log.w("SmsReceiver", e);
        message.setInvalidVersion(true);
      } catch (InvalidKeyIdException e) {
        Log.w("SmsReceiver", e);
        message.setStale(true);
      } catch (UntrustedIdentityException e) {
        Log.w("SmsReceiver", e);
      } catch (DuplicateMessageException e) {
        Log.w("SmsReceiver", e);
        message.setDuplicate(true);
      } catch (LegacyMessageException e) {
        Log.w("SmsReceiver", e);
        message.setLegacyVersion(true);
      }
    }

    return storeStandardMessage(masterSecret, message);
  }

  private Pair<Long, Long> storeKeyExchangeMessage(MasterSecret masterSecret,
                                                   IncomingKeyExchangeMessage message)
  {
    if (masterSecret != null && TextSecurePreferences.isAutoRespondKeyExchangeEnabled(context)) {
      try {
        Recipient            recipient       = RecipientFactory.getRecipientsFromString(context, message.getSender(), false).getPrimaryRecipient();
        RecipientDevice      recipientDevice = new RecipientDevice(recipient.getRecipientId(), message.getSenderDeviceId());
        KeyExchangeMessage   exchangeMessage = new KeyExchangeMessage(Base64.decodeWithoutPadding(message.getMessageBody()));
        KeyExchangeProcessor processor       = new KeyExchangeProcessor(context, masterSecret, recipientDevice);
        long                 threadId        = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(new Recipients(recipient));
        OutgoingKeyExchangeMessage response = processor.processKeyExchangeMessage(exchangeMessage, threadId);

        message.setProcessed(true);

        Pair<Long, Long> messageAndThreadId = storeStandardMessage(masterSecret, message);

        if (response != null) {
          MessageSender.send(context, masterSecret, response, messageAndThreadId.second, true);
        }

        return messageAndThreadId;
      } catch (InvalidVersionException e) {
        Log.w("SmsReceiver", e);
        message.setInvalidVersion(true);
      } catch (InvalidMessageException | InvalidKeyException | IOException | RecipientFormattingException e) {
        Log.w("SmsReceiver", e);
        message.setCorrupted(true);
      } catch (LegacyMessageException e) {
        Log.w("SmsReceiver", e);
        message.setLegacyVersion(true);
      } catch (StaleKeyExchangeException e) {
        Log.w("SmsReceiver", e);
        message.setStale(true);
      } catch (UntrustedIdentityException e) {
        Log.w("SmsReceiver", e);
      }
    }

    return storeStandardMessage(masterSecret, message);
  }

  private Pair<Long, Long> storeMessage(MasterSecret masterSecret, IncomingTextMessage message) {
    if      (message.isSecureMessage()) return storeSecureMessage(masterSecret, message);
    else if (message.isPreKeyBundle())  return storePreKeyWhisperMessage(masterSecret, (IncomingPreKeyBundleMessage) message);
    else if (message.isKeyExchange())   return storeKeyExchangeMessage(masterSecret, (IncomingKeyExchangeMessage) message);
    else if (message.isEndSession())    return storeSecureMessage(masterSecret, message);
    else                                return storeStandardMessage(masterSecret, message);
  }

  private void handleReceiveMessage(MasterSecret masterSecret, Intent intent) {
    if (intent.getExtras() == null) return;

    List<IncomingTextMessage> messagesList = intent.getExtras().getParcelableArrayList("text_messages");
    IncomingTextMessage       message      = assembleMessageFragments(messagesList);

    if (message != null) {
      Pair<Long, Long> messageAndThreadId = storeMessage(masterSecret, message);
      MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
    }
  }

  public void process(MasterSecret masterSecret, Intent intent) {
    if (SendReceiveService.RECEIVE_SMS_ACTION.equals(intent.getAction())) {
      handleReceiveMessage(masterSecret, intent);
    }
  }
}

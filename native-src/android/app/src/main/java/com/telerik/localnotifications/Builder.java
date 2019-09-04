package com.telerik.localnotifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutionException;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

public final class Builder {

    public static final String NOTIFICATION_ID = "NOTIFICATION_ID";

    private static final String TAG = "Builder";
    private static final String DEFAULT_CHANNEL = "Miscellaneous";

    private static final int DEFAULT_NOTIFICATION_COLOR = Color.parseColor("#ffffffff");
    private static final int DEFAULT_NOTIFICATION_LED_ON = 500;
    private static final int DEFAULT_NOTIFICATION_LED_OFF = 2000;

    // Methods to build notifications:

    static Notification build(JSONObject options, Context context, int notificationID) {
        // We use options.channel as both channel id and name. If not set, both default to DEFAULT_CHANNEL:
        return build(options, context, notificationID, options.optString("channel", DEFAULT_CHANNEL));
    }

    static Notification build(JSONObject options, Context context, int notificationID, String channelID) {


        // Per channel AND per notification, for compatibility with pre-channel Android:

        // Vibration & Pattern
        boolean hasVibration = false;
        long[] vibratePattern = null;
        if (options.has("vibratePattern")) {
            JSONArray vibratePatternEncoded = options.optJSONArray("vibratePattern");

            hasVibration = true;

            if (vibratePatternEncoded != null) {
                vibratePattern = new long[vibratePatternEncoded.length()];

                for (int i = 0; i < vibratePatternEncoded.length(); ++i) {
                    try {
                        vibratePattern[i] = (vibratePatternEncoded.getLong(i));
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing vibration duration at index " + i, e);
                    }

                }
            }
        }


        // Set channel for Android 8+:

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            final NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            int finalImportance = NotificationManager.IMPORTANCE_DEFAULT;
            if (options.has("importance")) {
                int channelImportance = options.optInt("importance", 0);
                // New system uses a 0-5 scale
                switch (channelImportance) { //can't just add 3, apparently - app freezes
                    case -2: finalImportance = NotificationManager.IMPORTANCE_MIN; break;
                    case -1: finalImportance = NotificationManager.IMPORTANCE_LOW; break;
                    case 0:  break;
                    case 1: finalImportance = NotificationManager.IMPORTANCE_HIGH; break;
                    case 2: finalImportance = NotificationManager.IMPORTANCE_HIGH; break; // Apparently "MAX" isn't allowed right now
                }

            } else if (options.has("forceShowWhenInForeground")) {
                finalImportance = options.optBoolean("forceShowWhenInForeground", false) ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT;
                // Not really sure that's a correct interpretation of "priority", but for back-compat...
            }



            // Attempt to create a new channel every time, because some channel settings CAN be changed after creation:
            // name, description, group, importance (lower only)
            // see: https://developer.android.com/reference/android/app/NotificationManager.html#createNotificationChannel(android.app.NotificationChannel)
            // otherwise it'll ignore this and use existing channel anyway
            if (notificationManager != null && notificationManager.getNotificationChannel(channelID) == null) {
                NotificationChannel channel = new NotificationChannel(channelID, channelID, finalImportance);
                if (options.has("notificationLed")) {
                    channel.enableLights(true);
                    channel.setLightColor(getLedColor(options));
                }

                channel.enableVibration(hasVibration);

                if (vibratePattern != null) {
                    channel.setVibrationPattern(vibratePattern);
                }

                if (options.has("channelDescription")) {
                    channel.setDescription(options.optString("channelDescription", ""));
                }


                notificationManager.createNotificationChannel(channel);
            }
        }

        // Create the builder:

        NotificationCompat.Builder builder = android.os.Build.VERSION.SDK_INT >= 26 ? new NotificationCompat.Builder(context, channelID) : new NotificationCompat.Builder(context);



        builder
            .setDefaults(0)
            .setContentTitle(options.optString("title", null))
            .setSubText(options.optString("subtitle", null))
            .setContentText(options.optString("body", null))
            .setSmallIcon(options.optInt("icon"))
            .setAutoCancel(options.optBoolean("autoCancel", true)) // Remove the notification from the status bar once tapped.
            .setNumber(options.optInt("badge"))
            .setColor(options.optInt("color"))
            .setOngoing(options.optBoolean("ongoing"))
            .setTicker(options.optString("ticker", null))  // Let the OS handle the default value for the ticker.
            .setTimeoutAfter(options.optInt("expiresAfter", 0)) // Zero seems to work as "none"
            .setVibrate(vibratePattern) // for pre-Channels Android
            .setPriority(options.optInt("importance", 0)); //for pre-Channels Android

        final Object thumbnail = options.opt("thumbnail");

        if (thumbnail instanceof String) {
            builder.setLargeIcon(getBitmap(context, (String) thumbnail));
        }

        // TODO sound preference is not doing anything
        // builder.setSound(options.has("sound") ? Uri.parse("android.resource://" + context.getPackageName() + "%s/raw/%s" + options.getString("sound")) : Uri.parse("android.resource://" + context.getPackageName() + "/raw/notify"))
        if (options.has("sound")) {
            builder.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION));
        }

        applyImportance(options, builder);
        applyProgressBar(options, builder);
        applyNotificationLed(options, builder);
        applyStyle(options, builder, context);
        applyTapReceiver(options, builder, context, notificationID);
        applyClearReceiver(builder, context, notificationID);
        applyActions(options, builder, context, notificationID);

        return builder.build();
    }


    // Notification styles:



    private static void applyImportance(JSONObject options, NotificationCompat.Builder builder) {
        if (options.has("importance")) {
            builder.setPriority(options.optInt("importance", 0));
        } else if (options.has("forceShowWhenInForeground")) {
            builder.setPriority(options.optBoolean("forceShowWhenInForeground", false) ? 1 : 0);
            // Not really sure that's a correct interpretation of "priority", but for back-compat...
        }
    }

    private static void applyProgressBar(JSONObject options, NotificationCompat.Builder builder) {
        if (options.has("progress")) {
            if (options.has("progressMax")) {
                builder.setProgress(options.optInt("progressMax", 100), options.optInt("progress", 0), false);
            } else {
                builder.setProgress(0, 100, true);
            }
        }
    }


    private static void applyNotificationLed(JSONObject options, NotificationCompat.Builder builder) {
        if (options.has("notificationLed")) {
            builder.setLights(getLedColor(options), options.optInt("ledOn", 100), options.optInt("ledOff", 100));
        }
    }

    private static void applyStyle(JSONObject options, NotificationCompat.Builder builder, Context context) {
        if (options.has("groupedMessages")) {
            applyGroup(options, builder);
        } else if (options.optBoolean("bigTextStyle")) {
            applyBigTextStyle(options, builder);
        } else if (options.has("image")) {
            applyImage(options, builder, context);
        }
    }

    private static void applyImage(JSONObject options, NotificationCompat.Builder builder, Context context) {
        Bitmap bitmap = getBitmap(context, options.optString("image", ""));

        if (bitmap == null) {
            return;
        }

        final NotificationCompat.BigPictureStyle bigPictureStyle = new NotificationCompat.BigPictureStyle().bigPicture(bitmap);

        builder.setStyle(bigPictureStyle);

        final Object thumbnail = options.opt("thumbnail");

        if (Boolean.TRUE.equals(thumbnail)) {
            builder.setLargeIcon(bitmap); // Set the thumbnail...
            bigPictureStyle.bigLargeIcon(null); // ...which goes away when expanded.
        }

    }

    private static void applyBigTextStyle(JSONObject options, NotificationCompat.Builder builder) {
        // set big text style (adds an 'expansion arrow' to the notification)
        if (options.optBoolean("bigTextStyle")) {
            final NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle();
            bigTextStyle.setBigContentTitle(options.optString("title"));
            bigTextStyle.bigText(options.optString("body"));
            builder.setStyle(bigTextStyle);
        }
    }

    private static void applyGroup(JSONObject options, NotificationCompat.Builder builder) {
        JSONArray groupedMessages = options.optJSONArray("groupedMessages");

        if (groupedMessages == null) {
            return;
        }

        final NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();

        // Sets a title for the Inbox in expanded layout
        // TODO: Is this needed? Should we add a different option for it (bigTitle)?
        inboxStyle.setBigContentTitle(options.optString("title", null));
        inboxStyle.setSummaryText(options.optString("groupSummary", null));

        int messagesToDisplay = Math.min(groupedMessages.length(), 5);

        for (int i = 0; i < messagesToDisplay; ++i) {
            try {
                inboxStyle.addLine(groupedMessages.getString(i));
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing message at index " + i, e);
            }

        }

        builder
            .setGroup("myGroup") // TODO not sure this needs to be configurable
            .setStyle(inboxStyle);
    }


    // Notification click and cancel handlers:

    /**
     * Add the intent that handles the event when the notification is clicked (which should launch the app).
     */
    private static void applyTapReceiver(JSONObject options, NotificationCompat.Builder builder, Context context, int notificationID) {
        final Intent intent = new Intent(context, NotificationActionReceiver.class)
                .putExtra(NOTIFICATION_ID, notificationID)
                .putExtra("NOTIFICATION_LAUNCH", options.optBoolean("launch", true))
                .setAction(Action.CLICK_ACTION_ID)
                .setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        builder.setContentIntent(PendingIntent.getService(
            context,
            notificationID,
            intent,
            FLAG_UPDATE_CURRENT
        ));
    }

    /**
    * Add the intent that handles the delete event (which is fired when the X or 'clear all'
    * was pressed in the notification center).
    */
    private static void applyClearReceiver(NotificationCompat.Builder builder, Context context, int notificationID) {
        final Intent intent = new Intent(context, NotificationClearedReceiver.class)
            .putExtra(NOTIFICATION_ID, notificationID);

        builder.setDeleteIntent(PendingIntent.getBroadcast(
            context,
            notificationID,
            intent,
            FLAG_UPDATE_CURRENT
        ));
    }

    private static void applyActions(JSONObject options, NotificationCompat.Builder builder, Context context, int notificationID) {
        Action[] actions = getActions(options, context);

        if (actions == null || actions.length == 0) {
            return;
        }

        NotificationCompat.Action.Builder btn;
        for (Action action : actions) {
            btn = new NotificationCompat.Action.Builder(
                    action.getIcon(),
                    action.getTitle(),
                    getPendingIntentForAction(options, context, action, notificationID));

            if (action.isWithInput()) {
                Log.d(TAG, "applyActions, isWithInput");
                btn.addRemoteInput(action.getInput());
            } else {
                Log.d(TAG, "applyActions, not isWithInput");
            }

            builder.addAction(btn.build());
        }
    }

    private static Action[] getActions(JSONObject options, Context context) {
        Object value = options.opt("actions");
        String groupId = null;
        JSONArray actions = null;
        ActionGroup group = null;

        if (value instanceof String) {
            groupId = (String) value;
        } else if (value instanceof JSONArray) {
            actions = (JSONArray) value;
        }

        if (groupId != null) {
            group = ActionGroup.lookup(groupId);
        } else if (actions != null && actions.length() > 0) {
            group = ActionGroup.parse(context, actions);
        }

        return (group != null) ? group.getActions() : null;
    }

    private static PendingIntent getPendingIntentForAction(JSONObject options, Context context, Action action, int notificationID) {
        Log.d(TAG, "getPendingIntentForAction action.id " + action.getId() + ", action.isLaunchingApp(): " + action.isLaunchingApp());
        Intent intent = new Intent(context, NotificationActionReceiver.class)
                .putExtra(NOTIFICATION_ID, options.optInt("id", 0))
                // TODO see https://github.com/katzer/cordova-plugin-local-notifications/blob/ca1374325bb27ec983332d55dcb6975d929bca4b/src/android/notification/Builder.java#L396
                .putExtra("NOTIFICATION_LAUNCH", action.isLaunchingApp())
                .setAction(action.getId())
                .setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        return PendingIntent.getService(context, notificationID, intent, FLAG_UPDATE_CURRENT);
    }

    // Utility methods:

    private static @Nullable Bitmap getBitmap(Context context, String src) {
        if (src.indexOf("res://") == 0) {
            final int resourceId = context.getResources().getIdentifier(src.substring(6), "drawable", context.getApplicationInfo().packageName);

            return resourceId == 0 ? null : android.graphics.BitmapFactory.decodeResource(context.getResources(), resourceId);
        } else if (src.indexOf("http") == 0) {
            try {
                return new DownloadFileFromUrl(src).execute().get();
            } catch (InterruptedException | ExecutionException e) {
                return null;
            }
        }

        return null;
    }


    private static int getLedColor(JSONObject options) {
        Object notificationLed = options.opt("notificationLed");

        if (Boolean.TRUE.equals(notificationLed)) {
            return options.optInt("color", DEFAULT_NOTIFICATION_COLOR);  //Use foreground color for LED for consistency
        } else if (notificationLed instanceof Integer) {
            return (int) notificationLed;
        } else {
            Log.e(TAG, "Unable to parse option.notificationLed, using default notification color");
            return DEFAULT_NOTIFICATION_COLOR;
        }
    }
}

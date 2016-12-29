package com.egood.cordova.plugins.magtekudynamo;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.CallbackContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import com.magtek.mobile.android.mtlib.MTCardDataState;
import com.magtek.mobile.android.mtlib.MTConnectionType;
import com.magtek.mobile.android.mtlib.MTSCRA;
import com.magtek.mobile.android.mtlib.MTSCRAEvent;
import com.magtek.mobile.android.mtlib.MTConnectionState;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.Handler.Callback;

public class MagTekUDynamoPlugin extends CordovaPlugin {

	private static final String TAG = "sapphire";

	public static final String RECORD_AUDIO = Manifest.permission.RECORD_AUDIO;
	public static final int RECORD_REQ_CODE = 0;
	public static final int RECORD_INIT_CODE = 1;
	public static final int PERMISSION_DENIED_ERROR = 400;

	// Message types sent from the BluetoothChatService Handler
	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_DEVICE_NAME = 4;
	public static final int MESSAGE_TOAST = 5;
	public static final int STATUS_IDLE = 1;
	public static final int STATUS_PROCESSCARD = 2;
//	private static final int MESSAGE_UPDATE_GUI = 6;
	public static final String CONFIGWS_URL = "https://deviceconfig.magensa.net/service.asmx";//Production URL

	private static final int CONFIGWS_READERTYPE = 0;
	private static final String CONFIGWS_USERNAME = "magtek";
	private static final String CONFIGWS_PASSWORD = "p@ssword";

	public static final String EXTRAS_CONNECTION_TYPE_VALUE_AUDIO = "Audio";
	public static final String EXTRAS_CONNECTION_TYPE_VALUE_BLE = "BLE";
	public static final String EXTRAS_CONNECTION_TYPE_VALUE_BLE_EMV = "BLEEMV";
	public static final String EXTRAS_CONNECTION_TYPE_VALUE_BLUETOOTH = "Bluetooth";
	public static final String EXTRAS_CONNECTION_TYPE_VALUE_USB = "USB";

	public static final String EXTRAS_CONNECTION_TYPE = "CONNECTION_TYPE";
	public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
	public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
	public static final String EXTRAS_AUDIO_CONFIG_TYPE = "AUDIO_CONFIG_TYPE";

	private AudioManager mAudioMgr;

	//	public static final String TOAST = "toast";

	private long m_ConnectStartTime=0;
	private long m_InterruptWaitTime=10000;

	public static final String DEVICE_NAME = "device_name";
	public static final String PARTIAL_AUTH_INDICATOR = "1";
	// Intent request codes
	private static final int REQUEST_CONNECT_DEVICE = 1;

	private MTSCRA mMTSCRA;

	private MTConnectionType m_connectionType;
	private String m_deviceName;
	private String m_deviceAddress;
	private String m_audioConfigType = "1";

	//private int miDeviceType=MagTekSCRA.DEVICE_TYPE_NONE;
	private Handler mSCRADataHandler = new Handler(new SCRAHandlerCallback());
	final headSetBroadCastReceiver mHeadsetReceiver = new headSetBroadCastReceiver();
	final NoisyAudioStreamReceiver mNoisyAudioStreamReceiver = new NoisyAudioStreamReceiver();

	String mStringLocalConfig;

	private MTConnectionState m_connectionState = MTConnectionState.Disconnected;


	private boolean mbAudioConnected;

	private long mLongTimerInterval;

	private int mIntCurrentStatus;

	private int mIntCurrentVolume;

	private String mStringAudioConfigResult;
	private CallbackContext mEventListenerCb;

	private void InitializeDevice() {
	}

	private void InitializeData() {
		mMTSCRA.clearBuffers();
		mLongTimerInterval = 0;

		mbAudioConnected = false;
		mIntCurrentVolume = 0;
		mIntCurrentStatus = STATUS_IDLE;
		// m_connectionState = MTConnectionState.Disconnected;

		mStringAudioConfigResult = "";

    	Log.i(TAG, "InitializeData");
  }

	private String getManualAudioConfig()
	{
		String config = "";

		try
		{
			String model = android.os.Build.MODEL.toUpperCase();

			if(model.contains("DROID RAZR") || model.toUpperCase().contains("XT910"))
			{
				config = "INPUT_SAMPLE_RATE_IN_HZ=48000,";
			}
			else if ((model.equals("DROID PRO"))||
					(model.equals("MB508"))||
					(model.equals("DROIDX"))||
					(model.equals("DROID2"))||
					(model.equals("MB525")))
			{
				config = "INPUT_SAMPLE_RATE_IN_HZ=32000,";
			}
			else if ((model.equals("GT-I9300"))||//S3 GSM Unlocked
					(model.equals("SPH-L710"))||//S3 Sprint
					(model.equals("SGH-T999"))||//S3 T-Mobile
					(model.equals("SCH-I535"))||//S3 Verizon
					(model.equals("SCH-R530"))||//S3 US Cellular
					(model.equals("SAMSUNG-SGH-I747"))||// S3 AT&T
					(model.equals("M532"))||//Fujitsu
					(model.equals("GT-N7100"))||//Notes 2
					(model.equals("GT-N7105"))||//Notes 2
					(model.equals("SAMSUNG-SGH-I317"))||// Notes 2
					(model.equals("SCH-I605"))||// Notes 2
					(model.equals("SCH-R950"))||// Notes 2
					(model.equals("SGH-T889"))||// Notes 2
					(model.equals("SPH-L900"))||// Notes 2
					(model.equals("GT-P3113")))//Galaxy Tab 2, 7.0

			{
				config = "INPUT_AUDIO_SOURCE=VRECOG,";
			}
			else if ((model.equals("XT907")))
			{
				config = "INPUT_WAVE_FORM=0,";
			}
			else
			{
				config = "INPUT_AUDIO_SOURCE=VRECOG,";
				//config += "PAN_MOD10_CHECKDIGIT=FALSE";
			}

		}
		catch (Exception ex)
		{

		}

		return config;
	}

	public boolean isDeviceOpened() {
		Log.i(TAG, "SCRADevice isDeviceOpened");
		return (m_connectionState == MTConnectionState.Connected);
	}

	public long openDevice() {
		Log.i(TAG, "SCRADevice openDevice");

		long result = -1;

		/*
		 * Do not allow a connect when a connecting is in progress to avoid
		 * getting the Android BLE stack into a non responsive state. This is
		 * added to deal with edge cases where one is connecting/disconnecting
		 * rapidly
		 */
		if(m_connectionType == MTConnectionType.BLEEMV)
		{
			if(m_connectionState!=MTConnectionState.Disconnected)
			{
				Log.i(TAG, "SCRADevice openDevice:Device Not Disconnected");
				return 0;
			}
		}
		m_ConnectStartTime = System.currentTimeMillis();

		if (mMTSCRA != null)
		{
			mMTSCRA.setConnectionType(m_connectionType);
			mMTSCRA.setAddress(m_deviceAddress);

			boolean enableRetry = false;
			mMTSCRA.setConnectionRetry(enableRetry);

			if (m_connectionType == MTConnectionType.Audio)
			{
				if (m_audioConfigType.equalsIgnoreCase("1"))
				{
					// Manual Configuration
					Log.i(TAG, "*** Manual Audio Config");
					mMTSCRA.setDeviceConfiguration(getManualAudioConfig());
				}
				else if (m_audioConfigType.equalsIgnoreCase("2"))
				{
					// Configuration File
					Log.i(TAG, "*** Audio Config From File -- Not Implemented");
					// startAudioConfigFromFile();
					return 0;
				}
				else if (m_audioConfigType.equalsIgnoreCase("3"))
				{
					// Configuration From Server
					Log.i(TAG, "*** Audio Config From Server -- Not Implemented");
					// startAudioConfigFromServer();
					return 0;
				}
			}

			mMTSCRA.openDevice();

			result = 0;
		}

		return result;
	}

	private PluginResult doInitStuff() {
		PluginResult pr = new PluginResult(PluginResult.Status.OK, true);
		if(mMTSCRA == null) {
			pluginInitializeAuthorized();
		}
		pr = new PluginResult(PluginResult.Status.OK, true);
		return pr;
	}

	private PluginResult doOpenStuff() {
		if(mMTSCRA == null) {
			InitializeDevice();
			pluginInitializeAuthorized();
		} else {
			Log.i(TAG, "no permission sequence,... already granted ... are we in a loop?");
		}

		PluginResult pr = null;

		if(true || mbAudioConnected) {

			long rv = openDevice();

			Log.i(TAG, "after open... connected -> " + isDeviceOpened() + ", " + rv);

			pr = new PluginResult(PluginResult.Status.OK, rv == 0L);
		} else {
			Log.i(TAG, "mbAudioConnected was false so... No reader attached.");
			pr = new PluginResult(PluginResult.Status.ERROR, "No reader attached.");
		}

		return pr;
	}


	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		PluginResult pr = new PluginResult(PluginResult.Status.ERROR, "Unhandled execute call: " + action);

		mEventListenerCb = callbackContext;

		Log.i(TAG, "action -> " + action);

		if(action.equals("openDevice")) {

			if(cordova.hasPermission(RECORD_AUDIO))
			{
				doOpenStuff();
			}
			else
			{
				getAudioPermission(RECORD_REQ_CODE);
			}

		}
		else if(action.equals("closeDevice")) {
			mMTSCRA.closeDevice();
			pr = new PluginResult(PluginResult.Status.OK, !mMTSCRA.isDeviceConnected());
		}
		else if(action.equals("isDeviceConnected")) {

			if(cordova.hasPermission(RECORD_AUDIO))
			{
				doInitStuff();
			}
			else
			{
				getAudioPermission(RECORD_INIT_CODE);
			}
		}
		else if(action.equals("isDeviceOpened")) {
			pr = new PluginResult(PluginResult.Status.OK, isDeviceOpened());
		}
		else if(action.equals("clearCardData")) {
			pr = new PluginResult(PluginResult.Status.OK);
		}
/*		else if(action.equals("setCardData")) {
			try {
				;
			}
		}
		else if(action.equals("getTrackDecodeStatus")) {
			try {
				;
			}
		}
*/
		else if(action.equals("getTrack1")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getTrack1());
		}
		else if(action.equals("getTrack2")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getTrack2());
		}
		else if(action.equals("getTrack3")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getTrack3());
		}
		else if(action.equals("getTrack1Masked")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getTrack1Masked());
		}
		else if(action.equals("getTrack2Masked")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getTrack2Masked());
		}
		else if(action.equals("getTrack3Masked")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getTrack3Masked());
		}
		else if(action.equals("getMagnePrintStatus")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getMagnePrintStatus());
		}
		else if(action.equals("getMagnePrint")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getMagnePrint());
		}
		else if(action.equals("getDeviceSerial")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getDeviceSerial());
		}
		else if(action.equals("getSessionID")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getSessionID());
		}
/*		else if(action.equals("setDeviceProtocolString")) {
			try {
				;
			}
		}
*/
		else if(action.equals("listenForEvents")) {
			pr = new PluginResult(PluginResult.Status.NO_RESULT);
			pr.setKeepCallback(true);

			mEventListenerCb = callbackContext;
		}
		else if(action.equals("getCardName")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getCardName());
		}
		else if(action.equals("getCardIIN")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getCardIIN());
		}
		else if(action.equals("getCardLast4")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getCardLast4());
		}
		else if(action.equals("getCardExpDate")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getCardExpDate());
		}
		else if(action.equals("getCardServiceCode")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getCardServiceCode());
		}
		else if(action.equals("getCardStatus")) {
			pr = new PluginResult(PluginResult.Status.OK, mMTSCRA.getCardStatus());
		}
/*
		else if(action.equals("setDeviceType")) {
			try {
				;
			}
		}
*/
		else if(action.equals("setDeviceType")) {
			;
		}

		callbackContext.sendPluginResult(pr);

		return true;
	}

	public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException
	{
		for(int r:grantResults)
		{
			if(r == PackageManager.PERMISSION_DENIED)
			{
				this.mEventListenerCb.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, PERMISSION_DENIED_ERROR));
				return;
			}
		}
		switch(requestCode)
		{
			case RECORD_REQ_CODE:
				Log.i(TAG, "continuing with open sequence");
				doOpenStuff();
				break;
			case RECORD_INIT_CODE:
				Log.i(TAG, "continuing with init sequence");
				pluginInitializeAuthorized();
				break;
		}
	}

	private void pluginInitializeAuthorized() {
		Log.i(TAG, "initializing audio sequences after permission grant");

		final Intent intent = cordova.getActivity().getIntent();
		String connectionType = intent.getStringExtra(EXTRAS_CONNECTION_TYPE);

		m_connectionType = MTConnectionType.Audio;
		// TODO... there are other types here... see the MagTek AndroidSDK sample

		if(mAudioMgr == null) {
			mAudioMgr = (AudioManager) cordova.getActivity().getSystemService(Context.AUDIO_SERVICE);
			Log.i(TAG, "create mAudioMgr " + mAudioMgr.toString());
		}

		if(mMTSCRA == null) {
			mMTSCRA = new MTSCRA(cordova.getActivity().getApplicationContext(), mSCRADataHandler);
			Log.i(TAG, "create mMTSCRA " + mMTSCRA.toString());
		}

		InitializeData();

		onResume(false);

		Log.i(TAG, "isDeviceConnected -> " + mMTSCRA.isDeviceConnected());

		mIntCurrentVolume = mAudioMgr.getStreamVolume(AudioManager.STREAM_MUSIC);
	}

	protected void getAudioPermission(int requestCode)
	{
		cordova.requestPermission(this, requestCode, RECORD_AUDIO);
	}

  	protected void pluginInitialize() {
	  	Log.i(TAG, "pluginInitialize");
	  	// cordova.getActivity().getApplicationContext().registerReceiver(mHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
	  	// cordova.getActivity().getApplicationContext().registerReceiver(mNoisyAudioStreamReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
  	}

  	@Override
    public void onResume(boolean multitasking) {
    	super.onResume(multitasking);

      Log.i(TAG, "onResume");

      cordova.getActivity().getApplicationContext().registerReceiver(mHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
      cordova.getActivity().getApplicationContext().registerReceiver(mNoisyAudioStreamReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity
        // returns.
    }

    @Override
    public void onDestroy() {
      Log.i(TAG, "onDestroy");

      cordova.getActivity().getApplicationContext().unregisterReceiver(mHeadsetReceiver);
        cordova.getActivity().getApplicationContext().unregisterReceiver(mNoisyAudioStreamReceiver);
        if (mMTSCRA != null)
            mMTSCRA.closeDevice();

    	super.onDestroy();
    }

	private void maxVolume() {
		mAudioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, mAudioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI);
	}

	private void minVolume() {
		mAudioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, mIntCurrentVolume, AudioManager.FLAG_SHOW_UI);
	}

	private void sendCardData() throws JSONException {
		JSONObject response = new JSONObject();

		response.put("Response.Type", mMTSCRA.getResponseType());
		response.put("Track.Status", mMTSCRA.getTrackDecodeStatus());
		response.put("Card.Status", mMTSCRA.getCardStatus());
		response.put("Encryption.Status", mMTSCRA.getEncryptionStatus());
		response.put("Battery.Level", mMTSCRA.getBatteryLevel());
//		response.put("Swipe.Count", mMTSCRA.getSwipeCount());
		response.put("Track.Masked", mMTSCRA.getMaskedTracks());
		response.put("MagnePrint.Status", mMTSCRA.getMagnePrintStatus());
		response.put("SessionID", mMTSCRA.getSessionID());
		response.put("Card.SvcCode", mMTSCRA.getCardServiceCode());
		response.put("Card.PANLength", mMTSCRA.getCardPANLength());
		response.put("KSN", mMTSCRA.getKSN());
		response.put("Device.SerialNumber", mMTSCRA.getDeviceSerial());
		response.put("TLV.CARDIIN", mMTSCRA.getTagValue("TLV_CARDIIN", ""));
		response.put("MagTekSN", mMTSCRA.getMagTekDeviceSerial());
		response.put("FirmPartNumber", mMTSCRA.getFirmware());
		response.put("TLV.Version", mMTSCRA.getTLVVersion());
		response.put("DevModelName", mMTSCRA.getDeviceName());
		response.put("MSR.Capability", mMTSCRA.getCapMSR());
		response.put("Tracks.Capability", mMTSCRA.getCapTracks());
		response.put("Encryption.Capability", mMTSCRA.getCapMagStripeEncryption());
		response.put("Card.IIN", mMTSCRA.getCardIIN());
		response.put("Card.Name", mMTSCRA.getCardName());
		response.put("Card.Last4", mMTSCRA.getCardLast4());
		response.put("Card.ExpDate", mMTSCRA.getCardExpDate());
		response.put("Track1.Masked", mMTSCRA.getTrack1Masked());
		response.put("Track2.Masked", mMTSCRA.getTrack2Masked());
		response.put("Track3.Masked", mMTSCRA.getTrack3Masked());
		response.put("Track1", mMTSCRA.getTrack1());
		response.put("Track2", mMTSCRA.getTrack2());
		response.put("Track3", mMTSCRA.getTrack3());
		response.put("MagnePrint", mMTSCRA.getMagnePrint());
		response.put("RawResponse", mMTSCRA.getResponseData());

		mEventListenerCb.success(response);
	}

	private void sendCardError() {
		mEventListenerCb.error("That card was not swiped properly. Please try again.");
	}

  private void setState(MTConnectionState deviceState)
  {
    m_connectionState = deviceState;
  }

	private class SCRAHandlerCallback implements Callback {
		public boolean handleMessage(Message msg) {
			try {
				Log.i(TAG, "*** Callback " + msg.what);
				switch(msg.what) {
					case MTSCRAEvent.OnDeviceConnectionStateChanged:
						Log.i(TAG, "OnDeviceConnectionStateChanged");

						MTConnectionState deviceState = (MTConnectionState) msg.obj;
						m_connectionState = deviceState;

						switch(deviceState) {
							case Connected:
								Log.i(TAG, "OnDeviceConnectionStateChanged.Connected");
								mIntCurrentStatus = STATUS_IDLE;
								maxVolume();
								break;

							case Connecting:
								Log.i(TAG, "OnDeviceConnectionStateChanged.Connecting");
								break;

							case Disconnected:
								Log.i(TAG, "OnDeviceConnectionStateChanged.Disconnected");
								minVolume();
								break;
						}
						break;

					case MTSCRAEvent.OnDataReceived:
						Log.i(TAG, "OnDataReceived");
						if(msg.obj != null) {
							sendCardData();
							msg.obj = null;
							return true;
						}
						break;

					case MTSCRAEvent.OnCardDataStateChanged:
						Log.i(TAG, "OnCardDataStateChanged");
						OnCardDataStateChanged((MTCardDataState) msg.obj);
						break;

					default:
						if(msg.obj != null) {
							return true;
						}
						break;
				};
			} catch(Exception ex) {
				;
			}

			return false;
		}
	}

	protected void OnCardDataStateChanged(MTCardDataState cardDataState)
	{
		switch (cardDataState)
		{
			case DataNotReady:
				Log.i(TAG, "[Card Data Not Ready]");
				break;
			case DataReady:
				Log.i(TAG, "[Card Data Ready]");
				break;
			case DataError:
				Log.i(TAG, "[Card Data Error]");
				sendCardError();
				break;
		}

	}


	public class NoisyAudioStreamReceiver extends BroadcastReceiver
    {
    	@Override
    	public void onReceive(Context context, Intent intent)
    	{
    		/* If the device is unplugged, this will immediately detect that action,
    		 * and close the device.
    		 */
    		if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()))
    		{
            	mbAudioConnected=false;

            	if(m_connectionType == MTConnectionType.Audio)
            	{
            		if(mMTSCRA.isDeviceConnected())
            		{
            			mMTSCRA.closeDevice();
            		}
            	}
    		}
    	}
    }

	public class headSetBroadCastReceiver extends BroadcastReceiver
    {

        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO Auto-generated method stub
        	try
        	{
                String action = intent.getAction();
                //Log.i("Broadcast Receiver", action);
                Log.i(TAG, "Broadcast Receiver -> " + action);
                if( (action.compareTo(Intent.ACTION_HEADSET_PLUG))  == 0)   //if the action match a headset one
                {
                    int headSetState = intent.getIntExtra("state", 0);      //get the headset state property
                    int hasMicrophone = intent.getIntExtra("microphone", 0);//get the headset microphone property
  				    //mCardDataEditText.setText("Headset.Detected=" + headSetState + ",Microphone.Detected=" + hasMicrophone);

					Log.i(TAG, "Broadcast Receiver -> headSetState -> " + headSetState);
					Log.i(TAG, "Broadcast Receiver -> hasMicrophone -> " + hasMicrophone);

					if( (headSetState == 1) && (hasMicrophone == 1))        //headset was unplugged & has no microphone
                    {
                      Log.i(TAG, "Broadcast Receiver -> mbAudioConnected -> true");
                      mbAudioConnected=true;
                    }
                    else
                    {
                      Log.i(TAG, "Broadcast Receiver -> mbAudioConnected -> false");
                    	mbAudioConnected=false;
						if(m_connectionType == MTConnectionType.Audio)
                    	{
                    		if(mMTSCRA.isDeviceConnected())
                    		{
                    			mMTSCRA.closeDevice();
                    		}
                    	}

                    }

                }

        	}
        	catch(Exception ex)
        	{
        	  ex.printStackTrace();
            Log.i(TAG, "Broadcast Receiver -> exception " + ex.getMessage());
          }

        }
    }
}

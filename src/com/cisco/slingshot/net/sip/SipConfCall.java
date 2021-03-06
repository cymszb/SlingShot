/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cisco.slingshot.net.sip;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.rtp.AudioCodec;
import android.net.rtp.AudioGroup;
import android.net.rtp.AudioStream;
import android.net.rtp.RtpStream;
import android.net.sip.SimpleSessionDescription;
import android.net.sip.SimpleSessionDescription.Media;
import android.net.sip.SipErrorCode;
import android.net.sip.SipException;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSession;
import android.net.wifi.WifiManager;
import android.os.Message;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.cisco.slingshot.exjabber.utils.CallStatusChangeListener;
import com.cisco.slingshot.net.rtp.RtpVideoCodec;
import com.cisco.slingshot.net.rtp.VideoStream;
import com.cisco.slingshot.service.SocketListenerService;
import com.cisco.slingshot.utils.Util;
/**
 * Handles an Internet audio call over SIP. You can instantiate this class with
 * {@link SipManagerExtd}, using {@link SipManagerExtd#makeAudioCall makeAudioCall()},
 * using {@link SipManagerExtd#makeConfCall makeConfCall()},
 * and {@link SipManagerExtd#takeAudioCall takeAudioCall()},
 * {@link SipManagerExtd#takeConfCall takeConfCall()}.
 * 
 * <p class="note">
 * <strong>Note:</strong> Using this class require the
 * {@link android.Manifest.permission#INTERNET} and
 * {@link android.Manifest.permission#USE_SIP} permissions.<br/>
 * <br/>
 * In addition, {@link #startAudio} requires the
 * {@link android.Manifest.permission#RECORD_AUDIO},
 * {@link android.Manifest.permission#ACCESS_WIFI_STATE}, and
 * {@link android.Manifest.permission#WAKE_LOCK} permissions; and
 * {@link #setSpeakerMode setSpeakerMode()} requires the
 * {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS} permission.
 * </p>
 */
public class SipConfCall {
	private static final String TAG = SipConfCall.class.getSimpleName();
	private static final boolean RELEASE_SOCKET = true;
	private static final boolean DONT_RELEASE_SOCKET = false;
	private static final int SESSION_TIMEOUT = 5; // in seconds
	private static final int TRANSFER_TIMEOUT = 15; // in seconds

	/**
	 * Listener for events relating to a SIP call, such as when a call is being
	 * recieved ("on ringing") or a call is outgoing ("on calling").
	 * <p>
	 * Many of these events are also received by {@link SipSession.Listener}.
	 * </p>
	 */
	public static class Listener {
		/**
		 * Called when the call object is ready to make another call. The
		 * default implementation calls {@link #onChanged}.
		 * 
		 * @param call
		 *            the call object that is ready to make another call
		 */
		public void onReadyToCall(SipConfCall call) {
			onChanged(call);
		}

		/**
		 * Called when a request is sent out to initiate a new call. The default
		 * implementation calls {@link #onChanged}.
		 * 
		 * @param call
		 *            the call object that carries out the audio call
		 */
		public void onCalling(SipConfCall call) {
			onChanged(call);
		}

		/**
		 * Called when a new call comes in. The default implementation calls
		 * {@link #onChanged}.
		 * 
		 * @param call
		 *            the call object that carries out the audio call
		 * @param caller
		 *            the SIP profile of the caller
		 */
		public void onRinging(SipConfCall call, SipProfile caller) {
			onChanged(call);
		}

		/**
		 * Called when a RINGING response is received for the INVITE request
		 * sent. The default implementation calls {@link #onChanged}.
		 * 
		 * @param call
		 *            the call object that carries out the audio call
		 */
		public void onRingingBack(SipConfCall call) {
			onChanged(call);
		}

		/**
		 * Called when the session is established. The default implementation
		 * calls {@link #onChanged}.
		 * 
		 * @param call
		 *            the call object that carries out the audio call
		 */
		public void onCallEstablished(SipConfCall call) {
			onChanged(call);
		}

		/**
		 * Called when the session is terminated. The default implementation
		 * calls {@link #onChanged}.
		 * 
		 * @param call
		 *            the call object that carries out the audio call
		 */
		public void onCallEnded(SipConfCall call) {
			onChanged(call);
		}

		/**
		 * Called when the peer is busy during session initialization. The
		 * default implementation calls {@link #onChanged}.
		 * 
		 * @param call
		 *            the call object that carries out the audio call
		 */
		public void onCallBusy(SipConfCall call) {
			onChanged(call);
		}

		/**
		 * Called when the call is on hold. The default implementation calls
		 * {@link #onChanged}.
		 * 
		 * @param call
		 *            the call object that carries out the audio call
		 */
		public void onCallHeld(SipConfCall call) {
			onChanged(call);
		}
		
		/**
		 * 
		 * @param call
		 */
		public void onCallInvisible(SipConfCall call){
			onChanged(call);
		}

		/**
		 * Called when an error occurs. The default implementation is no op.
		 * 
		 * @param call
		 *            the call object that carries out the audio call
		 * @param errorCode
		 *            error code of this error
		 * @param errorMessage
		 *            error message
		 * @see SipErrorCode
		 */
		public void onError(SipConfCall call, int errorCode, String errorMessage) {
			// no-op
		}

		/**
		 * Called when an event occurs and the corresponding callback is not
		 * overridden. The default implementation is no op. Error events are not
		 * re-directed to this callback and are handled in {@link #onError}.
		 */
		public void onChanged(SipConfCall call) {
			// no-op
		}
	}

	private Context mContext;
	private SipProfile mLocalProfile;
	private SipConfCall.Listener mListener;
	private SipSession mSipSession;
	private SipSession mTransferringSession;

	private long mSessionId = System.currentTimeMillis();
	private String mPeerSd;
	private String mLocalSd;

	private AudioStream mAudioStream;
	private AudioGroup mAudioGroup;
	/* indicate local video source */
	private InetAddress mLocalVideo;
	private int         mLocalVideoPort;
	private VideoStream mVideoRtp;
	private final static String KEY_RESOLUTION = "framesize";
	private static String mVideoResolution = "640-480";
	public static int V_480P = 1;
	public static int V_720P = 2;
	private String mVideoPort_Key = "video.local.port";
	private String mVideoPT_Key = "video.local.sdp.pt";
	private int mVideoPT;
	private boolean mInCall = false;
	private boolean mMuted = false;
	private boolean mHold = false;
	private boolean mInvisible = false; 

	private WifiManager mWm;
	private WifiManager.WifiLock mWifiHighPerfLock;

	private int mErrorCode = SipErrorCode.NO_ERROR;
	private String mErrorMessage;

	/**
	 * Creates a call object with the local SIP profile.
	 * 
	 * @param context
	 *            the context for accessing system services such as ringtone,
	 *            audio, WIFI etc
	 */
	public SipConfCall(Context context, SipProfile localProfile) {
		mContext = context;
		mLocalProfile = localProfile;
		try {
			mLocalVideoPort  = this.getRadomVideoPort();
			SystemProperties.set(mVideoPort_Key, Integer.toString(mLocalVideoPort));
			mLocalVideo = InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		try {
			mVideoRtp = new VideoStream(mLocalVideo);
			//mVideoRtp = new VideoStream(mLocalVideo,mLocalVideoPort);
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		mWm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
	}

	/**
	 * Sets the listener to listen to the audio call events. The method calls
	 * {@link #setListener setListener(listener, false)}.
	 * 
	 * @param listener
	 *            to listen to the audio call events of this object
	 * @see #setListener(Listener, boolean)
	 */
	public void setListener(SipConfCall.Listener listener) {
		setListener(listener, false);
	}

	/**
	 * Sets the listener to listen to the audio call events. A
	 * {@link SipConfCall} can only hold one listener at a time. Subsequent
	 * calls to this method override the previous listener.
	 * 
	 * @param listener
	 *            to listen to the audio call events of this object
	 * @param callbackImmediately
	 *            set to true if the caller wants to be called back immediately
	 *            on the current state
	 */
	public void setListener(SipConfCall.Listener listener,
			boolean callbackImmediately) {
		mListener = listener;
		try {
			if ((listener == null) || !callbackImmediately) {
				// do nothing
			} else if (mErrorCode != SipErrorCode.NO_ERROR) {
				listener.onError(this, mErrorCode, mErrorMessage);
			} else if (mInCall) {
				if (mHold) {
					listener.onCallHeld(this);
				} else {
					if(mInvisible){
						listener.onCallInvisible(this);
					}else{
						listener.onCallEstablished(this);
					}
					
				}
			} else {
				int state = getState();
				switch (state) {
				case SipSession.State.READY_TO_CALL:
					listener.onReadyToCall(this);
					break;
				case SipSession.State.INCOMING_CALL:
					listener.onRinging(this, getPeerProfile());
					break;
				case SipSession.State.OUTGOING_CALL:
					listener.onCalling(this);
					break;
				case SipSession.State.OUTGOING_CALL_RING_BACK:
					listener.onRingingBack(this);
					break;
				}
			}
		} catch (Throwable t) {
			Log.e(TAG, "setListener()", t);
		}
	}

	/**
	 * Checks if the call is established.
	 * 
	 * @return true if the call is established
	 */
	public boolean isInCall() {
		synchronized (this) {
			return mInCall;
		}
	}

	/**
	 * Checks if the call is on hold.
	 * 
	 * @return true if the call is on hold
	 */
	public boolean isOnHold() {
		synchronized (this) {
			return mHold;
		}
	}

	/**
	 * Closes this object. This object is not usable after being closed.
	 */
	public void close() {
		close(true);
	}

	private synchronized void close(boolean closeRtp) {
		if (closeRtp)
			stopCall(RELEASE_SOCKET);

		mInCall = false;
		mHold = false;
		mSessionId = System.currentTimeMillis();
		mErrorCode = SipErrorCode.NO_ERROR;
		mErrorMessage = null;

		if (mSipSession != null) {
			mSipSession.setListener(null);
			mSipSession = null;
		}
	}

	/**
	 * Gets the local SIP profile.
	 * 
	 * @return the local SIP profile
	 */
	public SipProfile getLocalProfile() {
		synchronized (this) {
			return mLocalProfile;
		}
	}

	/**
	 * Gets the peer's SIP profile.
	 * 
	 * @return the peer's SIP profile
	 */
	public SipProfile getPeerProfile() {
		synchronized (this) {
			return (mSipSession == null) ? null : mSipSession.getPeerProfile();
		}
	}

	/**
	 * Gets the state of the {@link SipSession} that carries this call. The
	 * value returned must be one of the states in {@link SipSession.State}.
	 * 
	 * @return the session state
	 */
	public int getState() {
		synchronized (this) {
			if (mSipSession == null)
				return SipSession.State.READY_TO_CALL;
			return mSipSession.getState();
		}
	}

	/**
	 * Gets the {@link SipSession} that carries this call.
	 * 
	 * @return the session object that carries this call
	 * @hide
	 */
	public SipSession getSipSession() {
		synchronized (this) {
			return mSipSession;
		}
	}

	private synchronized void transferToNewSession() {
		if (mTransferringSession == null)
			return;
		SipSession origin = mSipSession;
		mSipSession = mTransferringSession;
		mTransferringSession = null;

		// stop the replaced call.
		if (mAudioStream != null) {
			mAudioStream.join(null);
		} else {
			try {
				mAudioStream = new AudioStream(
						InetAddress.getByName(getLocalIp()));
			} catch (Throwable t) {
				Log.i(TAG, "transferToNewSession(): " + t);
			}
		}
		if (origin != null)
			origin.endCall();
		startAudio();
	}

	private SipSession.Listener createListener() {
		return new SipSession.Listener() {
			@Override
			public void onCalling(SipSession session) {
				Util.S_Log.d(TAG, "calling... " + session);
				Listener listener = mListener;
				if (listener != null) {
					try {
						listener.onCalling(SipConfCall.this);
					} catch (Throwable t) {
						Log.i(TAG, "onCalling(): " + t);
					}
				}
			}

			@Override
			public void onRingingBack(SipSession session) {
				Util.S_Log.d(TAG, "sip call ringing back: " + session);
				
				processListener(SocketListenerService.MSG_RINGINGBACK, null);
				
				Listener listener = mListener;
				if (listener != null) {
					try {
						listener.onRingingBack(SipConfCall.this);
					} catch (Throwable t) {
						Log.i(TAG, "onRingingBack(): " + t);
					}
				}
			}

			@Override
			public void onRinging(SipSession session, SipProfile peerProfile,
					String sessionDescription) {
				processListener(SocketListenerService.MSG_RINGINGBACK, null);
				// this callback is triggered only for reinvite.
				synchronized (SipConfCall.this) {
					if ((mSipSession == null)
							|| !mInCall
							|| !session.getCallId().equals(
									mSipSession.getCallId())) {
						// should not happen
						session.endCall();
						return;
					}

					// session changing request
					try {
						String answer = createAnswer(sessionDescription, true)
								.encode();
						mSipSession.answerCall(answer, SESSION_TIMEOUT);
					} catch (Throwable e) {
						Log.e(TAG, "onRinging()", e);
						session.endCall();
					}
				}
			}

			@Override
			public void onCallEstablished(SipSession session,
					String sessionDescription) {
				
				processListener(SocketListenerService.MSG_OK, null);
				
				mPeerSd = sessionDescription;
				Log.v(TAG, "onCallEstablished()" + mPeerSd);
				
				// start test code 
				//Util.S_Log.d(TAG, "getLocalVideoCodecProfile() = " + getLocalVideoCodecProfile());
				//Util.S_Log.d(TAG, "getLocaVideoCodecLevel() = " + getLocaVideoCodecLevel());
				//Util.S_Log.d(TAG, "getRemoteVideoCodecProfile() = " + getRemoteVideoCodecProfile());
				//Util.S_Log.d(TAG, "getRemoteVideoCodecLevel() = " + getRemoteVideoCodecLevel());
				// end test code

				// TODO: how to notify the UI that the remote party is changed
				if ((mTransferringSession != null)
						&& (session == mTransferringSession)) {
					transferToNewSession();
					return;
				}
				
				try {
					mVideoRtp.setRemoteAddress(getPeerVideoAddress(), getPeerVideoPort());
				} catch (UnknownHostException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (SipException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				Listener listener = mListener;
				if (listener != null) {
					try {
						if (mHold) {
							listener.onCallHeld(SipConfCall.this);
						} else {
							if(mInvisible){
								listener.onCallInvisible(SipConfCall.this);
							}else{
								listener.onCallEstablished(SipConfCall.this);
							}
						}
					} catch (Throwable t) {
						Log.i(TAG, "onCallEstablished(): " + t);
					}
				}
			}

			@Override
			public void onCallEnded(SipSession session) {
				Util.S_Log.d(TAG, "sip call ended: " + session + " mSipSession:"
						+ mSipSession);
				// reset the trasnferring session if it is the one.
				if (session == mTransferringSession) {
					mTransferringSession = null;
					return;
				}
				// or ignore the event if the original session is being
				// transferred to the new one.
				if ((mTransferringSession != null) || (session != mSipSession))
					return;

				Listener listener = mListener;
				if (listener != null) {
					try {
						listener.onCallEnded(SipConfCall.this);
					} catch (Throwable t) {
						Log.i(TAG, "onCallEnded(): " + t);
					}
				}
				
				processListener(SocketListenerService.MSG_END, null);
				
				close();
			}

			@Override
			public void onCallBusy(SipSession session) {
				processListener(SocketListenerService.MSG_BUSY, null);
				Util.S_Log.d(TAG, "sip call busy: " + session);
				Listener listener = mListener;
				if (listener != null) {
					try {
						listener.onCallBusy(SipConfCall.this);
					} catch (Throwable t) {
						Log.i(TAG, "onCallBusy(): " + t);
					}
				}
				close(false);
			}

			@Override
			public void onCallChangeFailed(SipSession session, int errorCode,
					String message) {
				Util.S_Log.d(TAG, "sip call change failed: " + message);
				mErrorCode = errorCode;
				mErrorMessage = message;
				Listener listener = mListener;
				if (listener != null) {
					try {
						listener.onError(SipConfCall.this, mErrorCode, message);
					} catch (Throwable t) {
						Log.i(TAG, "onCallBusy(): " + t);
					}
				}
			}

			@Override
			public void onError(SipSession session, int errorCode,
					String message) {
				
				if(mListener != null){
					mListener.onError(SipConfCall.this, errorCode, message);
				}
				Util.S_Log.d(TAG, "errorCode = " + errorCode + ", message = " + message);
				ArrayList<String> list = new ArrayList<String>();
				list.add(0, String.valueOf(errorCode));
				list.add(1, message);
				processListener(SocketListenerService.MSG_ERROR, list);
				SipConfCall.this.onError(errorCode, message);
			}

			@Override
			public void onRegistering(SipSession session) {
				// irrelevant
			}

			@Override
			public void onRegistrationTimeout(SipSession session) {
				// irrelevant
			}

			@Override
			public void onRegistrationFailed(SipSession session, int errorCode,
					String message) {
				// irrelevant
			}

			@Override
			public void onRegistrationDone(SipSession session, int duration) {
				// irrelevant
			}

			@Override
			public void onCallTransferring(SipSession newSession,
					String sessionDescription) {
				Log.v(TAG, "onCallTransferring mSipSession:" + mSipSession
						+ " newSession:" + newSession);
				mTransferringSession = newSession;
				try {
					if (sessionDescription == null) {
						newSession.makeCall(newSession.getPeerProfile(),
								createOffer().encode(), TRANSFER_TIMEOUT);
					} else {
						String answer = createAnswer(sessionDescription, true)
								.encode();
						newSession.answerCall(answer, SESSION_TIMEOUT);
					}
				} catch (Throwable e) {
					Log.e(TAG, "onCallTransferring()", e);
					newSession.endCall();
				}
			}
			
			
		};
	}

	private void onError(int errorCode, String message) {
		Util.S_Log.d(TAG, "sip session error: " + SipErrorCode.toString(errorCode)
				+ ": " + message);
		mErrorCode = errorCode;
		mErrorMessage = message;
		Listener listener = mListener;
		if (listener != null) {
			try {
				listener.onError(this, errorCode, message);
			} catch (Throwable t) {
				Log.i(TAG, "onError(): " + t);
			}
		}
		synchronized (this) {
			if ((errorCode == SipErrorCode.DATA_CONNECTION_LOST) || !isInCall()) {
				close(true);
			}
		}
	}

	/**
	 * Attaches an incoming call to this call object.
	 * 
	 * @param session
	 *            the session that receives the incoming call
	 * @param sessionDescription
	 *            the session description of the incoming call
	 * @throws SipException
	 *             if the SIP service fails to attach this object to the session
	 *             or VOIP API is not supported by the device
	 * @see SipManager#isVoipSupported
	 */
	public void attachCall(SipSession session, String sessionDescription)
			throws SipException {
		if (!SipManager.isVoipSupported(mContext)) {
			throw new SipException("VOIP API is not supported");
		}

		synchronized (this) {
			mSipSession = session;
			mPeerSd = sessionDescription;
			
			/*set remote video source*/
			try {
				mVideoRtp.setRemoteAddress(getPeerVideoAddress(), getPeerVideoPort());
			} catch (UnknownHostException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			
			Log.v(TAG, "attachCall()" + mPeerSd);
			
			try {
				session.setListener(createListener());
			} catch (Throwable e) {
				Log.e(TAG, "attachCall()", e);
				throwSipException(e);
			}
		}
	}

	/**
	 * Initiates an audio call to the specified profile. The attempt will be
	 * timed out if the call is not established within {@code timeout} seconds
	 * and {@link Listener#onError onError(SipAudioCall, SipErrorCode.TIME_OUT,
	 * String)} will be called.
	 * 
	 * @param peerProfile
	 *            the SIP profile to make the call to
	 * @param sipSession
	 *            the {@link SipSession} for carrying out the call
	 * @param timeout
	 *            the timeout value in seconds. Default value (defined by SIP
	 *            protocol) is used if {@code timeout} is zero or negative.
	 * @see Listener#onError
	 * @throws SipException
	 *             if the SIP service fails to create a session for the call or
	 *             VOIP API is not supported by the device
	 * @see SipManager#isVoipSupported
	 */
	public void makeCall(SipProfile peerProfile, SipSession sipSession,
			int timeout) throws SipException {
		if (!SipManager.isVoipSupported(mContext)) {
			throw new SipException("VOIP API is not supported");
		}

		synchronized (this) {
			mSipSession = sipSession;
			try {
				mAudioStream = new AudioStream(
						InetAddress.getByName(getLocalIp()));
				sipSession.setListener(createListener());
				sipSession.makeCall(peerProfile, createOffer().encode(),
						timeout);
			} catch (IOException e) {
				throw new SipException("makeCall()", e);
			}
		}
	}

	public void makeConfCall(SipProfile peerProfile, SipSession sipSession,
			int timeout) throws SipException {
		if (!SipManager.isVoipSupported(mContext)) {
			throw new SipException("VOIP API is not supported");
		}

		synchronized (this) {
			mSipSession = sipSession;
			try {
				mAudioStream = new AudioStream(
						InetAddress.getByName(getLocalIp()));
				sipSession.setListener(createListener());
				sipSession.makeCall(peerProfile, createConfOffer().encode(),
						timeout);
			} catch (IOException e) {
				throw new SipException("makeCall()", e);
			}
		}
	}

	/**
	 * Ends a call.
	 * 
	 * @throws SipException
	 *             if the SIP service fails to end the call
	 */
	public void endCall() throws SipException {
		synchronized (this) {
			stopCall(RELEASE_SOCKET);
			mInCall = false;

			// perform the above local ops first and then network op
			if (mSipSession != null)
				mSipSession.endCall();
		}
	}

	/**
	 * Puts a call on hold. When succeeds, {@link Listener#onCallHeld} is
	 * called. The attempt will be timed out if the call is not established
	 * within {@code timeout} seconds and {@link Listener#onError
	 * onError(SipAudioCall, SipErrorCode.TIME_OUT, String)} will be called.
	 * 
	 * @param timeout
	 *            the timeout value in seconds. Default value (defined by SIP
	 *            protocol) is used if {@code timeout} is zero or negative.
	 * @see Listener#onError
	 * @throws SipException
	 *             if the SIP service fails to hold the call
	 */
	public void holdCall(int timeout) throws SipException {
		synchronized (this) {
			if (mHold)
				return;
			if (mSipSession == null) {
				throw new SipException("Not in a call to hold call");
			}
			mSipSession.changeCall(createHoldOffer().encode(), timeout);
			mHold = true;
			setAudioGroupMode();
		}
	}

	/**
	 * Answers a call. The attempt will be timed out if the call is not
	 * established within {@code timeout} seconds and {@link Listener#onError
	 * onError(SipAudioCall, SipErrorCode.TIME_OUT, String)} will be called.
	 * 
	 * @param timeout
	 *            the timeout value in seconds. Default value (defined by SIP
	 *            protocol) is used if {@code timeout} is zero or negative.
	 * @see Listener#onError
	 * @throws SipException
	 *             if the SIP service fails to answer the call
	 */
	public void answerCall(int timeout) throws SipException {
		synchronized (this) {
			if (mSipSession == null) {
				throw new SipException("No call to answer");
			}
			try {
				mAudioStream = new AudioStream(
						InetAddress.getByName(getLocalIp()));
				mSipSession.answerCall(createAnswer(mPeerSd, false).encode(),
						timeout);
			} catch (IOException e) {
				throw new SipException("answerCall()", e);
			}
		}
	}

	/**
	 * Answers a video call. The attempt will be timed out if the call is not
	 * established within {@code timeout} seconds and {@link Listener#onError
	 * onError(SipAudioCall, SipErrorCode.TIME_OUT, String)} will be called.
	 * 
	 * @param timeout
	 *            the timeout value in seconds. Default value (defined by SIP
	 *            protocol) is used if {@code timeout} is zero or negative.
	 * @see Listener#onError
	 * @throws SipException
	 *             if the SIP service fails to answer the call
	 */
	public void answerConfCall(int timeout) throws SipException {
		synchronized (this) {
			if (mSipSession == null) {
				throw new SipException("No call to answer");
			}
			try {
				mAudioStream = new AudioStream(
						InetAddress.getByName(getLocalIp()));
				/*set remote video source ip and port*/
				try {
					mVideoRtp.setRemoteAddress(getPeerVideoAddress(), getPeerVideoPort());
				} catch (UnknownHostException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
				
				mSipSession.answerCall(createAnswer(mPeerSd, true).encode(),
						timeout);
			} catch (IOException e) {
				throw new SipException("answerCall()", e);
			}
		}
	}

	/**
	 * Continues a call that's on hold. When succeeds,
	 * {@link Listener#onCallEstablished} is called. The attempt will be timed
	 * out if the call is not established within {@code timeout} seconds and
	 * {@link Listener#onError onError(SipAudioCall, SipErrorCode.TIME_OUT,
	 * String)} will be called.
	 * 
	 * @param timeout
	 *            the timeout value in seconds. Default value (defined by SIP
	 *            protocol) is used if {@code timeout} is zero or negative.
	 * @see Listener#onError
	 * @throws SipException
	 *             if the SIP service fails to unhold the call
	 */
	public void continueCall(int timeout) throws SipException {
		synchronized (this) {
			if (!mHold)
				return;
			mSipSession.changeCall(createContinueOffer().encode(), timeout);
			mHold = false;
			setAudioGroupMode();
		}
	}

	private SimpleSessionDescription createOffer() {
		SimpleSessionDescription offer = new SimpleSessionDescription(
				mSessionId, getLocalIp());

		Media media = offer.newMedia("audio", mAudioStream.getLocalPort(), 1,
				"RTP/AVP");
		for (AudioCodec codec : AudioCodec.getCodecs()) {
			media.setRtpPayload(codec.type, codec.rtpmap, codec.fmtp);
		}
		media.setRtpPayload(127, "telephone-event/8000", "0-15");
		return offer;
	}

	private SimpleSessionDescription createConfOffer() {
		SimpleSessionDescription offer = new SimpleSessionDescription(
				mSessionId, getLocalIp());
		/* audio media */

		Media media = offer.newMedia("audio", mAudioStream.getLocalPort(), 1,
				"RTP/AVP");
		media.setBandwidth("AS", 64); // AS unit is kbps
		for (AudioCodec codec : AudioCodec.getCodecs()) {
			media.setRtpPayload(codec.type, codec.rtpmap, codec.fmtp);
		}
		media.setRtpPayload(127, "telephone-event/8000", "0-15");

		/* video media */
		Media v_media = offer.newMedia("video", getLocalVideoPort(), 1,
				"RTP/AVP");
		v_media.setBandwidth("AS", 5000); // AS unit is kbps
		for (RtpVideoCodec codec : RtpVideoCodec.VIDEOCODEC) {
			v_media.setRtpPayload(codec.type, codec.rtpmap, codec.fmtp);
			v_media.setAttribute(KEY_RESOLUTION, codec.type +" " + mVideoResolution);
			mVideoPT = codec.type;
		}

		SystemProperties.set(mVideoPT_Key, Integer.toString(mVideoPT));
		mLocalSd = offer.encode();
		return offer;
	}

	private SimpleSessionDescription createAnswer(String offerSd,
			boolean is_conf) {
		if (TextUtils.isEmpty(offerSd))
			return createOffer();
		SimpleSessionDescription offer = new SimpleSessionDescription(offerSd);
		SimpleSessionDescription answer = new SimpleSessionDescription(
				mSessionId, getLocalIp());
		AudioCodec codec = null;
		for (Media media : offer.getMedia()) {
			if ((codec == null) && (media.getPort() > 0)
					&& "audio".equals(media.getType())
					&& "RTP/AVP".equals(media.getProtocol())) {
				// Find the first audio codec we supported.
				for (int type : media.getRtpPayloadTypes()) {
					codec = AudioCodec.getCodec(type, media.getRtpmap(type),
							media.getFmtp(type));
					if (codec != null) {
						break;
					}
				}
				if (codec != null) {
					Media reply = answer.newMedia("audio",
							mAudioStream.getLocalPort(), 1, "RTP/AVP");
					reply.setRtpPayload(codec.type, codec.rtpmap, codec.fmtp);

					// Check if DTMF is supported in the same media.
					for (int type : media.getRtpPayloadTypes()) {
						String rtpmap = media.getRtpmap(type);
						if ((type != codec.type) && (rtpmap != null)
								&& rtpmap.startsWith("telephone-event")) {
							reply.setRtpPayload(type, rtpmap,
									media.getFmtp(type));
						}
					}

					// Handle recvonly and sendonly.
					if (media.getAttribute("recvonly") != null) {
						answer.setAttribute("sendonly", "");
					} else if (media.getAttribute("sendonly") != null) {
						answer.setAttribute("recvonly", "");
					} else if (offer.getAttribute("recvonly") != null) {
						answer.setAttribute("sendonly", "");
					} else if (offer.getAttribute("sendonly") != null) {
						answer.setAttribute("recvonly", "");
					}
					continue;
				}
			}
			
/*			// Reject the media.
			Media reply = answer.newMedia(media.getType(), 0, 1,
					media.getProtocol());
			for (String format : media.getFormats()) {
				reply.setFormat(format, null);
			}*/
		}
		if (codec == null) {
			throw new IllegalStateException("Reject SDP: no suitable codecs");
		}
		
		if(is_conf)
		{/*check video attribute*/
			for (Media media : offer.getMedia()) {
			/* create video answer */
			if ((media.getPort() > 0)
					&& "video".equals(media.getType())
					&& "RTP/AVP".equals(media.getProtocol())) {
					// Find the first video codec we supported.
					RtpVideoCodec v_codec = null;
					for (int type : media.getRtpPayloadTypes()) {
						v_codec = RtpVideoCodec.getCodec(type, media.getRtpmap(type),
								media.getFmtp(type));
						if (v_codec != null) {
							break;
						}
					}

					if(null != v_codec){
						Media replay = answer.newMedia("video",
								getLocalVideoPort(), 1, "RTP/AVP");
						replay.setBandwidth("AS", 5000); // AS unit is kbps
						replay.setRtpPayload(v_codec.type, v_codec.rtpmap,
								v_codec.fmtp);
						mVideoPT = v_codec.type;
						SystemProperties.set(mVideoPT_Key, Integer.toString(mVideoPT));
						/*check whether it had attribute*/
						String resolution = media.getAttribute(KEY_RESOLUTION);
						if(null != resolution && true != resolution.equals("")){
							replay.setAttribute(KEY_RESOLUTION, resolution);
						}
					}
					break;
			}
			}
		}
		mLocalSd = answer.encode();
		
		Util.S_Log.d(TAG, mLocalSd);
		return answer;
	}

	private SimpleSessionDescription createHoldOffer() {
		SimpleSessionDescription offer = createContinueOffer();
		offer.setAttribute("sendonly", "");
		return offer;
	}

	private SimpleSessionDescription createContinueOffer() {
		SimpleSessionDescription offer = new SimpleSessionDescription(
				mSessionId, getLocalIp());
		Media media = offer.newMedia("audio", mAudioStream.getLocalPort(), 1,
				"RTP/AVP");
		AudioCodec codec = mAudioStream.getCodec();
		media.setRtpPayload(codec.type, codec.rtpmap, codec.fmtp);
		int dtmfType = mAudioStream.getDtmfType();
		if (dtmfType != -1) {
			media.setRtpPayload(dtmfType, "telephone-event/8000", "0-15");
		}
		return offer;
	}
	
	//for video start/shutdown
	private SimpleSessionDescription createVideoChangeOffer(){
		return null;
	}

	private void grabWifiHighPerfLock() {
		if (mWifiHighPerfLock == null) {
			Log.v(TAG, "acquire wifi high perf lock");
			mWifiHighPerfLock = ((WifiManager) mContext
					.getSystemService(Context.WIFI_SERVICE)).createWifiLock(
					WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG);
			mWifiHighPerfLock.acquire();
		}
	}

	private void releaseWifiHighPerfLock() {
		if (mWifiHighPerfLock != null) {
			Log.v(TAG, "release wifi high perf lock");
			mWifiHighPerfLock.release();
			mWifiHighPerfLock = null;
		}
	}

	private boolean isWifiOn() {
		return (mWm.getConnectionInfo().getBSSID() == null) ? false : true;
	}

	/** Toggles mute. */
	public void toggleMute() {
		synchronized (this) {
			mMuted = !mMuted;
			setAudioGroupMode();
		}
	}

	/**
	 * Checks if the call is muted.
	 * 
	 * @return true if the call is muted
	 */
	public boolean isMuted() {
		synchronized (this) {
			return mMuted;
		}
	}

	/**
	 * Puts the device to speaker mode.
	 * <p class="note">
	 * <strong>Note:</strong> Requires the
	 * {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS} permission.
	 * </p>
	 * 
	 * @param speakerMode
	 *            set true to enable speaker mode; false to disable
	 */
	public void setSpeakerMode(boolean speakerMode) {
		synchronized (this) {
			((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
					.setSpeakerphoneOn(speakerMode);
			setAudioGroupMode();
		}
	}

	private boolean isSpeakerOn() {
		return ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
				.isSpeakerphoneOn();
	}

	/**
	 * Sends a DTMF code. According to <a
	 * href="http://tools.ietf.org/html/rfc2833">RFC 2883</a>, event 0--9 maps
	 * to decimal value 0--9, '*' to 10, '#' to 11, event 'A'--'D' to 12--15,
	 * and event flash to 16. Currently, event flash is not supported.
	 * 
	 * @param code
	 *            the DTMF code to send. Value 0 to 15 (inclusive) are valid
	 *            inputs.
	 */
	public void sendDtmf(int code) {
		sendDtmf(code, null);
	}

	/**
	 * Sends a DTMF code. According to <a
	 * href="http://tools.ietf.org/html/rfc2833">RFC 2883</a>, event 0--9 maps
	 * to decimal value 0--9, '*' to 10, '#' to 11, event 'A'--'D' to 12--15,
	 * and event flash to 16. Currently, event flash is not supported.
	 * 
	 * @param code
	 *            the DTMF code to send. Value 0 to 15 (inclusive) are valid
	 *            inputs.
	 * @param result
	 *            the result message to send when done
	 */
	public void sendDtmf(int code, Message result) {
		synchronized (this) {
			AudioGroup audioGroup = getAudioGroup();
			if ((audioGroup != null) && (mSipSession != null)
					&& (SipSession.State.IN_CALL == getState())) {
				Log.v(TAG, "send DTMF: " + code);

				audioGroup.sendDtmf(code);
			}
			if (result != null)
				result.sendToTarget();
		}
	}

	/**
	 * Gets the {@link AudioStream} object used in this call. The object
	 * represents the RTP stream that carries the audio data to and from the
	 * peer. The object may not be created before the call is established. And
	 * it is undefined after the call ends or the {@link #close} method is
	 * called.
	 * 
	 * @return the {@link AudioStream} object or null if the RTP stream has not
	 *         yet been set up
	 * @hide
	 */
	public AudioStream getAudioStream() {
		synchronized (this) {
			return mAudioStream;
		}
	}

	/**
	 * Gets the {@link AudioGroup} object which the {@link AudioStream} object
	 * joins. The group object may not exist before the call is established.
	 * Also, the {@code AudioStream} may change its group during a call (e.g.,
	 * after the call is held/un-held). Finally, the {@code AudioGroup} object
	 * returned by this method is undefined after the call ends or the
	 * {@link #close} method is called. If a group object is set by
	 * {@link #setAudioGroup(AudioGroup)}, then this method returns that object.
	 * 
	 * @return the {@link AudioGroup} object or null if the RTP stream has not
	 *         yet been set up
	 * @see #getAudioStream
	 * @hide
	 */
	public AudioGroup getAudioGroup() {
		synchronized (this) {
			if (mAudioGroup != null)
				return mAudioGroup;
			return ((mAudioStream == null) ? null : mAudioStream.getGroup());
		}
	}

	/**
	 * Sets the {@link AudioGroup} object which the {@link AudioStream} object
	 * joins. If {@code audioGroup} is null, then the {@code AudioGroup} object
	 * will be dynamically created when needed. Note that the mode of the
	 * {@code AudioGroup} is not changed according to the audio settings (i.e.,
	 * hold, mute, speaker phone) of this object. This is mainly used to merge
	 * multiple {@code SipAudioCall} objects to form a conference call. The
	 * settings of the first object (that merges others) override others'.
	 * 
	 * @see #getAudioStream
	 * @hide
	 */
	public void setAudioGroup(AudioGroup group) {
		synchronized (this) {
			if ((mAudioStream != null) && (mAudioStream.getGroup() != null)) {
				mAudioStream.join(group);
			}
			mAudioGroup = group;
		}
	}

	/**
	 * Starts the audio for the established call. This method should be called
	 * after {@link Listener#onCallEstablished} is called.
	 * <p class="note">
	 * <strong>Note:</strong> Requires the
	 * {@link android.Manifest.permission#RECORD_AUDIO},
	 * {@link android.Manifest.permission#ACCESS_WIFI_STATE} and
	 * {@link android.Manifest.permission#WAKE_LOCK} permissions.
	 * </p>
	 */
	public void startAudio() {
		try {
			startAudioInternal();
		} catch (UnknownHostException e) {
			onError(SipErrorCode.PEER_NOT_REACHABLE, e.getMessage());
		} catch (Throwable e) {
			onError(SipErrorCode.CLIENT_ERROR, e.getMessage());
		}
	}

	private synchronized void startAudioInternal() throws UnknownHostException {
		if (mPeerSd == null) {
			Log.v(TAG, "startAudioInternal() mPeerSd = null");
			throw new IllegalStateException("mPeerSd = null");
		}

		stopCall(DONT_RELEASE_SOCKET);
		mInCall = true;

		// Run exact the same logic in createAnswer() to setup mAudioStream.
		SimpleSessionDescription offer = new SimpleSessionDescription(mPeerSd);
		AudioStream stream = mAudioStream;
		AudioCodec codec = null;
		for (Media media : offer.getMedia()) {
			if ((codec == null) && (media.getPort() > 0)
					&& "audio".equals(media.getType())
					&& "RTP/AVP".equals(media.getProtocol())) {
				// Find the first audio codec we supported.
				for (int type : media.getRtpPayloadTypes()) {
					codec = AudioCodec.getCodec(type, media.getRtpmap(type),
							media.getFmtp(type));
					if (codec != null) {
						break;
					}
				}

				if (codec != null) {
					// Associate with the remote host.
					String address = media.getAddress();
					if (address == null) {
						address = offer.getAddress();
					}
					stream.associate(InetAddress.getByName(address),
							media.getPort());

					stream.setDtmfType(-1);
					stream.setCodec(codec);
					// Check if DTMF is supported in the same media.
					for (int type : media.getRtpPayloadTypes()) {
						String rtpmap = media.getRtpmap(type);
						if ((type != codec.type) && (rtpmap != null)
								&& rtpmap.startsWith("telephone-event")) {
							stream.setDtmfType(type);
						}
					}

					// Handle recvonly and sendonly.
					if (mHold) {
						stream.setMode(RtpStream.MODE_NORMAL);
					} else if (media.getAttribute("recvonly") != null) {
						stream.setMode(RtpStream.MODE_SEND_ONLY);
					} else if (media.getAttribute("sendonly") != null) {
						stream.setMode(RtpStream.MODE_RECEIVE_ONLY);
					} else if (offer.getAttribute("recvonly") != null) {
						stream.setMode(RtpStream.MODE_SEND_ONLY);
					} else if (offer.getAttribute("sendonly") != null) {
						stream.setMode(RtpStream.MODE_RECEIVE_ONLY);
					} else {
						stream.setMode(RtpStream.MODE_NORMAL);
					}
					break;
				}
			}
		}
		if (codec == null) {
			throw new IllegalStateException("Reject SDP: no suitable codecs");
		}

		if (isWifiOn())
			grabWifiHighPerfLock();

		// AudioGroup logic:
		AudioGroup audioGroup = getAudioGroup();
		if (mHold) {
			// don't create an AudioGroup here; doing so will fail if
			// there's another AudioGroup out there that's active
		} else {
			if (audioGroup == null)
				audioGroup = new AudioGroup();
			stream.join(audioGroup);
		}
		setAudioGroupMode();
	}

	// set audio group mode based on current audio configuration
	private void setAudioGroupMode() {
		AudioGroup audioGroup = getAudioGroup();
		if (audioGroup != null) {
			if (mHold) {
				audioGroup.setMode(AudioGroup.MODE_ON_HOLD);
			} else if (mMuted) {
				audioGroup.setMode(AudioGroup.MODE_MUTED);
			} else if (isSpeakerOn()) {
				audioGroup.setMode(AudioGroup.MODE_ECHO_SUPPRESSION);
			} else {
				audioGroup.setMode(AudioGroup.MODE_NORMAL);
			}
		}
	}

	private void stopCall(boolean releaseSocket) {
		Util.S_Log.d(TAG, "stop audiocall");
		releaseWifiHighPerfLock();
		if (mAudioStream != null) {
			mAudioStream.join(null);

			if (releaseSocket) {
				mAudioStream.release();
				mAudioStream = null;
			}
		}

		if (mVideoRtp != null && (true == releaseSocket)) {
			mVideoRtp.release();
			mVideoRtp = null;
		}
	}

	private String getLocalIp() {
		return mSipSession.getLocalIp();
	}

	private void throwSipException(Throwable throwable) throws SipException {
		if (throwable instanceof SipException) {
			throw (SipException) throwable;
		} else {
			throw new SipException("", throwable);
		}
	}

	/**
	 * Called when an call was established .
	 * 
	 * @return the remote video ip address
	 */	
	public String getPeerVideoAddress() {
		SimpleSessionDescription offer = new SimpleSessionDescription(mPeerSd);
		String vidaddr = null ; 
		for (Media media : offer.getMedia()) {
			if ("video".equals(media.getType())
					&& "RTP/AVP".equals(media.getProtocol())) {
				vidaddr = media.getAddress();
			}
		}
		if(null == vidaddr){
			vidaddr = offer.getAddress();
		}
		return vidaddr;
	}

	/**
	 * Called before doing a call or accept a incoming call.
	 * the default resolution was 480P.
	 * 
	 * @param resolution
	 *            SipConfCall.V_480P  indicated 480P resolution
	 *            SipConfCall.V_720P  indicated 720P resolution
	 */		
	public static void setVideoResolution(int resolution){
		
		if(V_720P == resolution){
			mVideoResolution = new String("1280-720");
		} else{
			mVideoResolution = new String("640-480");
		}
		
		return;
	}

	/**
	 * Called after a call was established .
	 * 
	 * @return the remote video ip port , if did't discover,
	 * it will return the 0xFFFF.
	 */
	public int getPeerVideoPort() {
		SimpleSessionDescription offer = new SimpleSessionDescription(mPeerSd);
		
//		return 5634;
		
		for (Media media : offer.getMedia()) {
			if ("video".equals(media.getType())
					&& "RTP/AVP".equals(media.getProtocol())) {
				return media.getPort();
			}
		}
		return 0xFFFF;
	}

	/**
	 * Called after a call was established .
	 * 
	 * @return the remote video transport protocol.
	 */
	public String getPeerVideoProtocol() {
		SimpleSessionDescription offer = new SimpleSessionDescription(mPeerSd);

		for (Media media : offer.getMedia()) {
			if ("video".equals(media.getType())) {
				return media.getProtocol();
			}
		}
		return null;
	}

	/**
	 * Called after a call was established .
	 * 
	 * @return the remote sdp , it didn't include the audio stream.
	 */
	public String getPeerSDP() {
		
		SimpleSessionDescription peersdp = new SimpleSessionDescription(mPeerSd);
		SimpleSessionDescription newsdp =  new SimpleSessionDescription(
				mSessionId, getPeerVideoAddress());
		Boolean flag = false ;
		for (Media media : peersdp.getMedia()) {
			 if("video".equals(media.getType())
				&& "RTP/AVP".equals(media.getProtocol())){
					for(int type : media.getRtpPayloadTypes()){
						if("H264/90000".equals(media.getRtpmap(type))) {
							Media replay = newsdp.newMedia("video",
									getLocalVideoPort(), 1, "RTP/AVP");
							replay.setRtpPayload(type, media.getRtpmap(type),
									media.getFmtp(type));
							/*check the frame size attribute*/
							String resolution = media.getAttribute(KEY_RESOLUTION);
							if((null != resolution) && (true != resolution.equals("")))
							replay.setAttribute(KEY_RESOLUTION, resolution);
							flag = true;
							break;
						}
				 	}
					if(flag)/*we got the peer sdp*/
						break;
			 }

		}
		String peersdpuri = "sdp://" + newsdp.encode();
		Log.v(TAG,mPeerSd);
		Log.v(TAG, peersdpuri);
		
		return peersdpuri;
	}

	/**
	 * Called when initiliaze a call or accept incoming video call .
	 * 
	 * @return the file descriptor of which video will be sent .
	 */
	public FileDescriptor getLocalVideoSocketFileDescripter() throws IOException {
		
		return mVideoRtp.getVideoFileDescriptor();
	}
	/**
	 * Called after a call was established .
	 * 
	 * @return the local video port .
	 */
	public int getLocalVideoPort() {
		/*default use 5004 according  RFC3551*/
		return mLocalVideoPort;
	}
	
	private int getRadomVideoPort(){
		//5000 ~ 65535
		/*
		final int portBase = 5004;
		int targetPort = portBase;
		int tmpPort =  (int)(java.lang.Math.random()*(65535 - portBase))  + portBase;
		
		//ensure the port number is a even
		if(tmpPort%2 == 0){
			targetPort =  tmpPort;
		}else{
			if(tmpPort + 1 > 65535){
				targetPort  = tmpPort - 1;
			}else{
				targetPort = tmpPort + 1;
			}
		}
		Util.S_Log.d(TAG, "Generate random local video port: " + targetPort);
		return targetPort;
		*/
		return 65432;
		
	}
	/**
	 * Called after a call was established .
	 * 
	 * @return the local video transport protocol .
	 */
	public String getLocalVideoProtocol() {
		SimpleSessionDescription offer = new SimpleSessionDescription(mLocalSd);

		for (Media media : offer.getMedia()) {
			if ("video".equals(media.getType())) {
				return media.getProtocol();
			}
		}
		return null;
	}
	/**
	 * Called after a call was established .
	 * 
	 * @return the local sdp.
	 */
	public String getLocalSDP() {

		return mLocalSd;
	}
	
	
	public void makeCallInvisible(int timeout)throws SipException{
		
		synchronized (this) {
			if (mHold)
				return;
			if (mSipSession == null) {
				throw new SipException("Not in a call to make call invisible");
			}
			mSipSession.changeCall(createInvisibleOffer().encode(), timeout);
			mInvisible = true;
		}
		
	}
	
	private SimpleSessionDescription createInvisibleOffer(){
			SimpleSessionDescription offer = createConfOffer();
			offer.setAttribute("videosendonly", "");
			return offer;
	}
	
	private String get_video_H264_profile_level_id(String sd)
	{
		SimpleSessionDescription sessionDescription = new SimpleSessionDescription(sd);
		
		for (Media media : sessionDescription.getMedia())
		{
			if ((media.getPort() > 0)
					&& "video".equals(media.getType())
					&& "RTP/AVP".equals(media.getProtocol()))
			{
				for (int type : media.getRtpPayloadTypes()) 
				{
					String rtpmap = media.getRtpmap(type);
					if(rtpmap.contains("H264/90000"))
					{
						String fmtp = media.getFmtp(type);
						String[] sub_fmtp = fmtp.split(";");
						for(int i = 0; i < sub_fmtp.length; i++)
						{
							if(sub_fmtp[i].startsWith("profile-level-id="))
							{
								String ret = new String(sub_fmtp[i].replace("profile-level-id=", ""));
								return ret;
							}
						}
					}
				}
			}
		}
		
		return null;
	}
	

	private int Profile2EncoderAVC(int val)
	{
		switch(val)
		{
		case 66:
			return MediaRecorder.EncoderH264.ENCODER_H264ProfileBaseline;
		case 77:
			return MediaRecorder.EncoderH264.ENCODER_H264ProfileMain;
		case 88:
			return MediaRecorder.EncoderH264.ENCODER_H264ProfileExtended;
		case 100:
			return MediaRecorder.EncoderH264.ENCODER_H264ProfileHigh;
		case 110:
			return MediaRecorder.EncoderH264.ENCODER_H264ProfileHigh10;
		case 122:
			return MediaRecorder.EncoderH264.ENCODER_H264ProfileHigh422;
		case 244:
			return MediaRecorder.EncoderH264.ENCODER_H264ProfileHigh444;
		default:
			return 0xFF;
		}
	}
	
	private int Level2EncoderAVC(int val)
	{
		switch(val)
		{
		case 9:
			return MediaRecorder.EncoderH264.Encoder_H264Level1;
		case 10:
			return MediaRecorder.EncoderH264.Encoder_H264Level1b;
		case 11:
			return MediaRecorder.EncoderH264.Encoder_H264Level11;
		case 12:
			return MediaRecorder.EncoderH264.Encoder_H264Level12;
		case 13:
			return MediaRecorder.EncoderH264.Encoder_H264Level13;
		case 20:
			return MediaRecorder.EncoderH264.Encoder_H264Level2;
		case 21:
			return MediaRecorder.EncoderH264.Encoder_H264Level21;
		case 22:
			return MediaRecorder.EncoderH264.Encoder_H264Level22;
		case 30:
			return MediaRecorder.EncoderH264.Encoder_H264Level3;
		case 31:
			return MediaRecorder.EncoderH264.Encoder_H264Level31;
		case 32:
			return MediaRecorder.EncoderH264.Encoder_H264Level32;
		case 40:
			return MediaRecorder.EncoderH264.Encoder_H264Level4;
		case 41:
			return MediaRecorder.EncoderH264.Encoder_H264Level41;
		case 42:
			return MediaRecorder.EncoderH264.Encoder_H264Level42;
		case 50:
			return MediaRecorder.EncoderH264.Encoder_H264Level5;
		case 51:
			return MediaRecorder.EncoderH264.Encoder_H264Level51;
		default:
			return 0xFF;
		}
		
	}
	
	// "profile-level-id=428016", try to get "42"
	private int getProfileFromString(String profile_level_id)
	{
		if((null == profile_level_id) ||
				profile_level_id.length()!=6)
		{
			Log.e(TAG,"invalid profile_level_id( = " + profile_level_id + " )");
			return -1; // return error value
		}
		
		String profile_str = profile_level_id.substring(0, 2);
		
		int profile_int = 0;
		
		try
		{
			profile_int = Integer.parseInt(profile_str, 16);
		}
		catch(NumberFormatException e)
		{
			Log.e(TAG,"invalid profile_level_id( = " + profile_level_id + " )");
			e.printStackTrace();
			return -2;
		}
		
		return profile_int;
	}
	
	// "profile-level-id=428016", try to get "16"
	private int getLevelFromString(String profile_level_id)
	{
		if((null == profile_level_id) ||
				profile_level_id.length()!=6)
		{
			Log.e(TAG,"invalid profile_level_id( = " + profile_level_id + " )");
			return -1; // return error value
		}
		
		String level_str = profile_level_id.substring(4, 6);
		
		int level_int = 0;
		
		try
		{
			level_int = Integer.parseInt(level_str, 16);
		}
		catch(NumberFormatException e)
		{
			Log.e(TAG,"invalid profile_level_id( = " + profile_level_id + " )");
			e.printStackTrace();
			return -2;
		}
		
		return level_int;
	}
	
	public int getLocalVideoCodecProfile() 
	{
		String local_video_codec_profile_level_id = get_video_H264_profile_level_id(mLocalSd);
		Util.S_Log.d(TAG, "local_video_codec_profile_level_id = " + local_video_codec_profile_level_id);
		int profile = getProfileFromString(local_video_codec_profile_level_id);
		
		return Profile2EncoderAVC(profile);
	}
	
	public int getLocaVideoCodecLevel() 
	{
		String local_video_codec_profile_level_id = get_video_H264_profile_level_id(mLocalSd);
		int level = getLevelFromString(local_video_codec_profile_level_id);
		
		return Level2EncoderAVC(level);
	}
	
	public int getRemoteVideoCodecProfile() 
	{
		String remote_video_codec_profile_level_id = get_video_H264_profile_level_id(mPeerSd);
		int profile = getProfileFromString(remote_video_codec_profile_level_id);
		
		return Profile2EncoderAVC(profile);
	}
	
	public int getRemoteVideoCodecLevel() 
	{
		String remote_video_codec_profile_level_id = get_video_H264_profile_level_id(mPeerSd);
		int level = getLevelFromString(remote_video_codec_profile_level_id);
		
		return Level2EncoderAVC(level);
	}
	
	
	// add for Jabber <--> STB
	public static void registerCallListener(CallStatusChangeListener listener)
	{
		if((callListener != null) && (listener != null))
		{
			Util.S_Log.d(TAG, "callListener = " + callListener);
			Util.S_Log.d(TAG, "!!! (Complain) only support one listener now !!!");
		}
		
		callListener = listener;
	}
	
	public static void processListener(int msg, ArrayList<String> list)
	{
		Util.S_Log.d(TAG, "msg = " + msg);
		
		if(null != callListener)
			callListener.onStatusChange(msg, list);
		else
			Util.S_Log.d(TAG, "!!! callListener == null !!!" );
	}
	
	private static CallStatusChangeListener callListener = null;
}

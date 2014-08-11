package com.kaltura.hlsplayersdk;



import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.util.Log;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import android.widget.MediaController.MediaPlayerControl;
import android.widget.RelativeLayout;
import android.widget.VideoView;

import com.kaltura.hlsplayersdk.manifest.events.OnParseCompleteListener;
import com.kaltura.hlsplayersdk.manifest.ManifestParser;
import com.kaltura.hlsplayersdk.manifest.ManifestSegment;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Timer;


public class PlayerView extends SurfaceView implements
	OnParseCompleteListener, URLLoader.DownloadEventListener
{
	
	private final int STATE_STOPPED = 1;
	private final int STATE_PAUSED = 2;
	private final int STATE_PLAYING = 3;
	private final int STATE_SEEKING = 4;
	
	// Native Methods
	private native void InitNativeDecoder();
	private native void CloseNativeDecoder();
	private native void ResetPlayer();
	private native void PlayFile();
	private native void StopPlayer();
	private native void TogglePause();
	private native void SetSurface(Surface surface);
	private native int NextFrame();
	private native void FeedSegment(String url, int quality, double startTime);
	private native void SeekTo(double time); // seconds, not miliseconds - I'll change this later if it
	private native int GetState();
	private native void SetScreenSize(int width, int height);
	
	private static PlayerView currentPlayerView = null;
	
	public static void requestNextSegment()
	{
		if (currentPlayerView != null)
		{
			ManifestSegment seg = currentPlayerView.getStreamHandler().getNextFile(0);
			if (seg != null)
			{
				currentPlayerView.FeedSegment(seg.uri, 0, seg.startTime);
			}
		}
	}
	
	public static void requestSegmentForTime(double time)
	{
		if (currentPlayerView != null)
		{
			ManifestSegment seg = currentPlayerView.getStreamHandler().getFileForTime(time, 0);
			currentPlayerView.FeedSegment(seg.uri, 0, seg.startTime);
		}
	}
	
	private int frameDelay = 10;

	// This is our root manifest
	private ManifestParser mManifest = null;
	
	private URLLoader manifestLoader;
	private StreamHandler mStreamHandler = null;
	
	private int mTimeMS = 0;
		
	private Handler handler = new Handler();
	private Runnable runnable = new Runnable()
	{
		public void run()
		{
			Log.i("Runnable.run", "PlayState = " + GetState());
			if (GetState() == STATE_PLAYING)
			{
				
				//Log.i("Runnable.run", "Running!");
				mTimeMS = NextFrame();
				if (mPlayheadUpdateListener != null)
					mPlayheadUpdateListener.onPlayheadUpdated(mTimeMS);
				postDelayed(runnable, frameDelay);
			}
		}
	};
	
	// setVideoUrl()
	// Sets the video URL and initiates the download of the manifest
	
	public void setVideoUrl(String url) {
		Log.i("PlayerView.setVideoUrl", url);
		//layoutParams lp = this.getLayoutParams();
		stop(); // We don't call StopPlayer here because we want to stop everything, including the update pump
		ResetPlayer();
		manifestLoader = new URLLoader(this, null);
		manifestLoader.get(url);
	}
	
	@Override
	protected void onSizeChanged (int w, int h, int oldw, int oldh)
	{
		super.onSizeChanged(w, h, oldw, oldh);

		Log.i("PlayerView.setVideoUrl", "Set size to " + w + "x" + h);
		SetScreenSize(w, h);
		//getHolder().setFixedSize(w, h);
	}
	
	// Called when the manifest parser is complete. Once this is done, play can actually start
	
	public void onParserComplete(ManifestParser parser)
	{
		Log.i("PlayerView.onParserComplete", "Entered");
		mStreamHandler = new StreamHandler(parser);
		//mStreamHandler.initialize(parser);
		ManifestSegment seg = getStreamHandler().getFileForTime(0, 0);
		currentPlayerView.FeedSegment(seg.uri, 0, seg.startTime);
		seg = getStreamHandler().getNextFile(0);
		currentPlayerView.FeedSegment(seg.uri, 0, seg.startTime);
		play();
		//parser.dumpToLog();
	}
	
	@Override
	public void onDownloadComplete(URLLoader loader, String response)
	{
		mManifest = new ManifestParser();
		mManifest.setOnParseCompleteListener(this);
		mManifest.parse(response, loader.getRequestURI().toString());
	}
	
	
	public void onDownloadFailed(URLLoader loader, String response)
	{
		
	}
	
	// Class Methods
	public PlayerView(Context context)
	{
		
		super(context);
		try
		{
			System.loadLibrary("HLSPlayerSDK");
			InitNativeDecoder();
		}
		catch (Exception e)
		{
			
		}

		currentPlayerView = this;

		// Set the surface type.
		getHolder().setKeepScreenOn(true);
		getHolder().setFixedSize(320, 240);
		getHolder().setFormat(19);
		getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
	}
	
	
	public void close()
	{
		CloseNativeDecoder();
	}
	
	
	
	public String getVideoUrl()
	{
		return "Not Implemented";
	}
	
	public int getDuration()
	{
		if (mStreamHandler != null)
			return mStreamHandler.getDuration();
		return -1;
	}
	
	
	public int getCurrentPosition()
	{
		return mTimeMS;
	}
	
	public boolean isPlaying()
	{
		return GetState() == STATE_PLAYING;
	}
	

	public boolean getIsPlaying()
	{
		return isPlaying();
	}
	
	public void play()
	{
		SetSurface(getHolder().getSurface());
		PlayFile();
		this.postDelayed(runnable, frameDelay);
	}
	
	public boolean canPause()
	{
		return true;
	}
	
	public boolean canSeekBackward()
	{
		return false;
	}
	
	public boolean canSeekForward()
	{
		return false;		
	}
	
	public int getAudioSessionId()
	{
		return 1;
	}
	
	public int getBufferPercentage()
	{
		return 0;
	}
	
	public void pause()
	{
		TogglePause();
		if (isPlaying())
		{
			postDelayed(runnable, frameDelay);
		}
	}
	
	
	public void stop()
	{
		StopPlayer();
		try {
			Thread.sleep(frameDelay * 2);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	

	public void seek(int msec)
	{
		SeekTo(msec / 1000);
	}

	public StreamHandler getStreamHandler()
	{
		return mStreamHandler;
	}

}

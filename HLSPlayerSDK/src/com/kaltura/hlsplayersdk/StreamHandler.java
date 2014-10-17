package com.kaltura.hlsplayersdk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import android.util.Log;

import com.kaltura.hlsplayersdk.manifest.ManifestEncryptionKey;
import com.kaltura.hlsplayersdk.manifest.ManifestParser;
import com.kaltura.hlsplayersdk.manifest.ManifestPlaylist;
import com.kaltura.hlsplayersdk.manifest.ManifestSegment;
import com.kaltura.hlsplayersdk.manifest.ManifestStream;
import com.kaltura.playersdk.QualityTrack;
import com.kaltura.playersdk.types.TrackType;


// This is the confusingly named "HLSIndexHandler" from the flash HLSPlugin
// I'll change it, if anyone really hates the new name. It just makes more sense to me.
public class StreamHandler implements ManifestParser.ReloadEventListener {
	public int lastSegmentIndex = 0;
	public int altAudioIndex = -1;
	public double lastKnownPlaylistStartTime = 0.0;
	public int lastQuality = 0;
	public int targetQuality = 0;
	public ManifestParser altAudioManifest = null;
	public ManifestParser manifest = null;
	public ManifestParser reloadingManifest = null;
	public int reloadingQuality = 0;
	public String baseUrl = null;
	
	
	private Timer reloadTimer = null;
	private int sequenceSkips = 0;
	private boolean stalled = false;
	private HashMap<String, Integer> badManifestMap = new HashMap<String, Integer>();
	private static final int maxFailedManifestTries = 3; // The number of retries a manifest may occur before we give up on it and remove it from our manifest list.
	private static final int isTooFarBehind = 5; // How far behind a stream can be before we log a message warning of significant delays
	
	private long mTimerDelay = 10000;
	
	
	public StreamHandler(ManifestParser parser)
	{
		manifest = parser;
		for (int i = 0; i < manifest.playLists.size(); ++i)
		{
			ManifestPlaylist mp = manifest.playLists.get(i);
			if (mp.isDefault)
			{
				altAudioManifest = mp.manifest;
				altAudioIndex = i;
				break;
			}
		}
	}
	
	public boolean setAltAudioTrack(int index)
	{
		if (index < manifest.playLists.size())
		{
			if (index < 0)
			{
				altAudioManifest = null;
				altAudioIndex = -1;
			}
			else 
			{
				altAudioIndex = index;
				altAudioManifest = manifest.playLists.get(altAudioIndex).manifest;
			}
			return true;
		}
		else
		{
			return false;
		}
	}
	
	public int getAltAudioDefaultIndex()
	{
		for (int i = 0; i < manifest.playLists.size(); ++i)
		{
			ManifestPlaylist mp = manifest.playLists.get(i);
			if (mp.isDefault)
			{
				return i; 
			}
		}
		return -1;
	}
	
	public int getAltAudioCurrentIndex()
	{
		return altAudioIndex;
	}
	
	public String[] getAltAudioLanguages()
	{
		if (manifest.playLists.size() == 0) return null;
		String[] languages = new String[manifest.playLists.size()];
		for (int i = 0; i < manifest.playLists.size(); ++i)
		{
			languages[i] = manifest.playLists.get(i).language;
		}
		return languages;
	}
	
	public List<String> getAltAudioLanguageList()
	{
		if (manifest.playLists.size() == 0) return null;
		List<String> languages = new ArrayList<String>();
		for (int i = 0; i < manifest.playLists.size(); ++i)
		{
			languages.add(manifest.playLists.get(i).language);
		}
		return languages;
	}
	
	
	public boolean hasAltAudio()
	{
		return manifest.playLists.size() > 0;
	}
	private TimerTask reloadTimerComplete = new TimerTask()
	{
		public void run()
		{
			Log.i("reloadTimerComplete.run", "Reload Timer Complete!");
			if (targetQuality != lastQuality)
				reload(targetQuality);
			else
				reload(lastQuality);
//			postDelayed(runnable, frameDelay);
		}
		
	};
	
	public List<QualityTrack> getQualityTrackList()
	{
		List<QualityTrack> tracks = new ArrayList<QualityTrack>();
		if (manifest.streams.size() > 0)
		{
			for (int i = 0; i < manifest.streams.size(); ++i)
			{
				ManifestStream s = manifest.streams.get(i);

				QualityTrack t = new QualityTrack();
				t.bitrate = s.bandwidth;
				t.width = s.width;
				t.height = s.height;
				t.trackId = i +  "|" + s.programId + "|" + s.uri;
				t.type = TrackType.VIDEO;
				tracks.add(t);			
				
			}
		}
		else
		{
			QualityTrack t = new QualityTrack();
			t.trackId = "0|0|" + manifest.fullUrl;
			t.type = TrackType.VIDEO;
			tracks.add(t);	
		}
		return tracks;
	}
	
	public void initialize()
	{
		postRatesReady();
		postIndexReady();
		updateTotalDuration();
		
		ManifestParser man = getManifestForQuality(lastQuality);
		if (man != null && !man.streamEnds && man.segments.size() > 0)
		{
			mTimerDelay = (long) man.segments.get(man.segments.size() - 1).duration * 1000;
			reloadTimer = new Timer();
			reloadTimer.schedule(reloadTimerComplete, mTimerDelay);
		}
	}
	
	private void reload(int quality)
	{
		if (reloadTimer != null)
			reloadTimer.cancel(); // In case the timer is active - don't want to do another reload in the middle of it
		
		reloadingQuality = quality;
		
		ManifestParser manToReload = getManifestForQuality(reloadingQuality);
		reloadingManifest = new ManifestParser();
		reloadingManifest.type = manToReload.type;
		reloadingManifest.setReloadEventListener(this);
		reloadingManifest.reload(manToReload);
		
	}
	
	private void startReloadTimer()
	{
		if (reloadTimer != null) reloadTimer.schedule(reloadTimerComplete, mTimerDelay);
	}
	
	@Override
	public void onReloadComplete(ManifestParser parser) {
		Log.i("StreamHandler.onReloadComplete", "last/reload/target: " + lastQuality + "/" + reloadingQuality + "/" + targetQuality);
		ManifestParser newManifest = parser;
		if (newManifest != null)
		{
			// Set the timer delay to the most likely possible delay
			if (reloadTimer != null) mTimerDelay = (long)(newManifest.segments.get(newManifest.segments.size() - 1).duration * 1000);
			
			// remove the reload completed listener since this might become the new manifest
			//newManifest.removeEventListener(Event.COMPLETE, onReloadComplete);
			newManifest.setReloadEventListener(null);
			
			ManifestParser currentManifest = getManifestForQuality(reloadingQuality);
			
			long timerOnErrorDelay = (long)(currentManifest.targetDuration * 1000  / 2);
			
			// If we're not switching quality
			if (reloadingQuality == lastQuality)
			{				
				if (newManifest.mediaSequence > currentManifest.mediaSequence)
				{
					updateManifestSegments(newManifest, reloadingQuality);
				}
				else if (newManifest.mediaSequence == currentManifest.mediaSequence && newManifest.segments.size() != currentManifest.segments.size())
				{
					updateManifestSegments(newManifest, reloadingQuality);
				}
				else
				{
					// the media sequence is earlier than the one we currently have, which isn't
					// allowed by the spec, or there are no changes. So do nothing, but check again as quickly as allowed
					if (reloadTimer != null) mTimerDelay = timerOnErrorDelay;
				}
			}
			else if (reloadingQuality == targetQuality)
			{
				if (!updateManifestSegmentsQualityChange(newManifest, reloadingQuality) && reloadTimer != null)
					mTimerDelay = timerOnErrorDelay;
			}

		}
		//TODO: dispatchDVRStreamInfo();
		reloadingManifest = null; // don't want to hang on to it
		startReloadTimer();
		
	}

	@Override
	public void onReloadFailed(ManifestParser parser) {
		if (reloadTimer != null)
		{
			mTimerDelay = (long)(getManifestForQuality(lastQuality).targetDuration * 1000  / 2);
			startReloadTimer();
		}
		
		// Keep track of how many times this particular manifest has failed to reload
		if (!badManifestMap.containsKey(parser.fullUrl))
		{
			badManifestMap.put(parser.fullUrl, 1);
		}
		else
		{
			badManifestMap.put(parser.fullUrl, badManifestMap.get(parser.fullUrl) + 1);
		}
		
		// Only continue on to removing the manifest if it has had an error enough times
		if (badManifestMap.get(parser.fullUrl) < maxFailedManifestTries)
			return;
		
		for (int i = 0; i < manifest.streams.size(); ++i)
		{
			ManifestStream curStream = manifest.streams.get(i);
			
			// We continue to th enext available stream if the url/uri doesn't match
			if (!parser.fullUrl.equals(curStream.manifest.fullUrl))
				continue;
			
			// We don't do anything if this is the lowest quality stream and there is no backup
			if (i == 0 && curStream.backupStream == null)
				break;
			
			// Replace the stream with its backup if possible
			if (curStream.backupStream != null)
			{
				// Remove the bad stream from the linked list, preserving the list's circular behavior
				while (curStream.backupStream != manifest.streams.get(i))
				{
					curStream = curStream.backupStream;
				}
				
				curStream.backupStream = curStream.backupStream.backupStream;
				
				// Check if this stream only has one backup
				if (curStream == curStream.backupStream)
					curStream.backupStream = null;
				
				manifest.streams.set(i, curStream);
			}
			else
			{
				// If there is no backup available, simply remove the stream from our stream list
				manifest.streams.remove(i);
				break;
				
			}
		}
		
		postRatesReady();
	}
	
	private boolean updateManifestSegmentsQualityChange(ManifestParser newManifest, int quality)
	{
		if (newManifest == null || newManifest.segments.size() == 0) return true;
		
		ManifestParser lastQualityManifest = getManifestForQuality(lastQuality);
		ManifestParser targetManifest = getManifestForQuality(quality);

		if (newManifest.isDVR() != lastQualityManifest.isDVR())
		{
			// If the new manifest's DVR status does not match the current DVR status, don't switch qualities
			targetQuality = lastQuality;
			return false;
		}

		Vector<ManifestSegment> lastQualitySegments = lastQualityManifest.segments;
		Vector<ManifestSegment> targetSegments = targetManifest.segments;
		Vector<ManifestSegment> newSegments = newManifest.segments;

		ManifestSegment matchSegment = lastQualitySegments.get(lastSegmentIndex);

		// Add the new manifest segments to the targetManifest
		// Tasks: (in order)
		// 	1) Append the new segments to the target segment list and determine the new last known playlist start time
		//	2) Determine the last segment index in the new segment list
		
		// Find the point where the new segments id matches the old segment id
		int matchId = targetSegments.get(targetSegments.size() - 1).id;
		int matchIndex = -1;
		double matchStartTime = lastKnownPlaylistStartTime;
		for (int i = newSegments.size() - 1; i >= 0; --i)
		{
			if (newSegments.get(i).id == matchId)
			{
				matchIndex = i;
				break;
			}
		}
		
		// We only need to make additional calculations if we were able to find a point where the segments matched up
		if (matchIndex >= 0 && matchIndex != newSegments.size() - 1)
		{
			// Fix the start times
			double nextStartTime = targetSegments.get(targetSegments.size()-1).startTime;
			for (int i = matchIndex; i < newSegments.size(); ++i)
			{
				newSegments.get(i).startTime = nextStartTime;
				nextStartTime += newSegments.get(i).duration;
			}
			
			// Append the new manifest segments to the targetManifest
			for (int i = matchIndex + 1; i < newSegments.size(); ++i)
			{
				targetSegments.add(newSegments.get(i));
			}
			
			// Now we need to calculate the last known playlist start time
			int matchStartId = newSegments.get(0).id;
			for (int i = 0; i < targetSegments.size(); ++i)
			{
				if (targetSegments.get(i).id == matchStartId)
					matchStartTime = targetSegments.get(i).startTime;
			}
		}
		else if (matchIndex < 0)
		{
			// The last playlist start time is at the start of the newest segment, the best we can do here is estimate
			matchStartTime += targetSegments.get(targetSegments.size() - 1).duration;
			
			// No matches were foun so we add all the new segments to the playlist, also adjust their start times
			double nextStartTime = matchStartTime;
			for (int i = 0; i < newSegments.size(); ++i)
			{
				newSegments.get(i).startTime = nextStartTime;
				nextStartTime += newSegments.get(i).duration;
				targetSegments.add(newSegments.get(i));
			}
		}
		else
		{
			// In this case, there are no new segments, and we don't acually need to do anything to the playlist
		}
		
		// This is now where our new playlist starts
		lastKnownPlaylistStartTime = matchStartTime;
		
		// Figure out what the new lastSegmentIndex is
		boolean found = false;
		double matchTime = lastQualitySegments.get(lastSegmentIndex).startTime;
		for (int i = 0; i < targetSegments.size(); ++i)
		{
			if (targetSegments.get(i).startTime <= matchTime && targetSegments.get(i).startTime > matchTime - targetSegments.get(i).duration)
			{
				lastSegmentIndex = i;
				found = true;
				stalled = false;
				break;
			}
		}
		
		if (!found && targetSegments.get(targetSegments.size() - 1).startTime < matchSegment.startTime)
		{
			Log.i("updateManifestSegmentsQualityChange()", "***STALL*** Target STart Time: " + targetSegments.get(targetSegments.size() - 1).startTime + " Match Start Time: " + matchSegment.startTime);
			
			stalled = true; // We want to stall because we don't know what the index should be as our new playlist is not quite caught up
			return found; // returning early so that we don't hcange lastQuality (we still need that value around)
		}
		
		// set lastQuality to targetQuality since we're finally all matched up
		lastQuality = quality;
		stalled = false;
		return found;
		
	}
	
	private boolean old_updateManifestSegmentsQualityChange(ManifestParser newManifest, int quality)
	{
		if (newManifest == null || newManifest.segments.size() == 0) return true;
		ManifestParser lastQualityManifest = getManifestForQuality(lastQuality);
		ManifestParser targetManifest = getManifestForQuality(quality);
		
		if (newManifest.isDVR() != lastQualityManifest.isDVR())
		{
			// If the new manifest's DVR status does not match the current DVR status, don't switch qualities
			targetQuality = lastQuality;
			return false;
		}
		
		Vector<ManifestSegment> lastQualitySegments = lastQualityManifest.segments;
		Vector<ManifestSegment> targetSegments = targetManifest.segments;
		Vector<ManifestSegment> newSegments = newManifest.segments;
		
		ManifestSegment matchSegment = lastQualitySegments.get(lastSegmentIndex);
		
		// Add the new manifest segments to the targetManifest
		// Goals: (not in order)
		//	1) Figure out what the start time of the new manifest is
		//	2) Append the newManifest to the targetManifest
		// 	3) Match lastQuality segments to the targetManifest segments by id
		//		a) set the seg start time
		//		b) set the seg era
		//	4) figure out what the new "lastSegmentIndex" is
		//	5) set lastQuality to the target quality
		
		// Starting with adjusting the segment values (so we only have to go through the new manifest info instead of the whole list)
		
		// Find the first segment (by id) of the new list in the lastQuality list by running backward through the lastQuality list
		int matchIndex = 0;
		int matchEra = 0;
		double matchStartTime = 0;
		for (int i = lastQualitySegments.size() - 1; i >= 0; --i)
		{
			if (lastQualitySegments.get(i).id == newSegments.get(0).id)
			{
				matchIndex = i;
				matchEra = lastQualitySegments.get(i).continuityEra;
				matchStartTime = lastQualitySegments.get(i).startTime;
				break;
			}
		}
		
		// Run through the new segments and fix up the start time, continuity era, and... hmm
		double nextStartTime = matchStartTime;
		for (int i = 0; i < newSegments.size(); ++i)
		{
			newSegments.get(i).continuityEra += matchEra;
			newSegments.get(i).startTime = nextStartTime;
			nextStartTime += newSegments.get(i).duration;		}
		
		// This is now where our new playlist starts
		lastKnownPlaylistStartTime = matchStartTime;
		
		// Append the new manifest segments to the targetManifest
		int idx = 0;
		if (newSegments.get(0).id < targetSegments.get(targetSegments.size() - 1).id)
		{
			for (int i = targetSegments.size() - 1; i >= 0; --i)
			{
				if (targetSegments.get(i).id == newSegments.get(0).id)
				{
					idx = i;
					break;
				}
			}
		}
		else
		{
			idx = targetSegments.size();
		}
		
		targetSegments.setSize(idx);
		//targetSegments.length = idx; // kill any matching segments since the start times and era will possibly be wrong
		for (int i = 0; i < newSegments.size(); ++i)
		{
			targetSegments.add(newSegments.get(i));
		}
		
		// Figure out what the new lastSegmentIndex is
		boolean found = false;
		for (int i = 0; i < targetSegments.size(); ++i)
		{
			if (targetSegments.get(i).id == matchSegment.id)
			{
				lastSegmentIndex = i;
				found = true;
				stalled = false;
				break;
			}
		}
		
		if (!found && targetSegments.get(targetSegments.size() - 1).id < matchSegment.id)
		{
			stalled = true; // We want to stall because we don't know what the index should be as our new playlist is not quite caught up
			return found; // Returning early so that we don't change lastQuality (we still need that value around)
		}
		else if (!found && targetSegments.get(targetSegments.size() - 1).id > lastQualitySegments.get(lastSegmentIndex).id)
		{
			// The new playlist is so far ahead of the old playlist that the item we wanted to play is gone. So go to the
			// first new index
			lastSegmentIndex = idx;
			found = true;
		}
		
		// set lastQuality to targetQuality since we're finally all matched up
		lastQuality = quality;
		stalled = false;
		return found;		
	}
	
	private void updateManifestSegments(ManifestParser newManifest, int quality)
	{
		// NOTE: If a stream uses byte ranges, the algorithm in this method will not
		// take note of them, and will likely return the same file every time. An effort
		// could also be made to do more stringent testing on the list of segments (beyond just the URI),
		// perhaps by comparing timestamps.
		
		if (newManifest == null || newManifest.segments.size() == 0) return;
		Vector<ManifestSegment> segments = getSegmentsForQuality( quality );
		ManifestParser curManifest = getManifestForQuality(quality);
		int segId = segments.get(segments.size() - 1).id;

		
		// Seek forward from the lastindex of the list (no need to start from 0) to match continuity eras
		int i = 0;
		int k = 0;
		int continuityOffset = 0;
		double newStartTime = 0;
		for (i = 0; i < segments.size(); ++i)
		{
			if (newManifest.segments.get(0).id == segments.get(i).id)
			{
				// Found the match. Now offset the eras in the new segment list by the era in the match
				continuityOffset = segments.get(i).continuityEra;
				newStartTime = segments.get(i).startTime;
				break;
			}
		}
		
		if (i == segments.size()) // we didn't find a match so force a discontinuity
		{
			if (segments.size() > 0)
			{
				continuityOffset = segments.get(segments.size()-1).continuityEra + 1;
				newStartTime = segments.get(segments.size()-1).startTime + segments.get(segments.size()-1).duration;
			}
		}

		// store the playlist start time
		lastKnownPlaylistStartTime = newStartTime;

		// run through the new playlist and adjust the start times and continuityEras
		for (k = 0; k < newManifest.segments.size(); ++k)
		{
			newManifest.segments.get(k).continuityEra += continuityOffset;
			newManifest.segments.get(k).startTime = newStartTime;
			newStartTime += newManifest.segments.get(k).duration;
		}
		
		// Seek backward through the new segment list until we find the one that matches
		// the last segment of the current list
		for (i = newManifest.segments.size() - 1; i >= 0; --i)
		{
			if (newManifest.segments.get(i).id == segId)
			{
				break;
			}
		}
		
//		// kill all the segments from the new list that match what we already have
//		newManifest.segments.splice(0, i + 1);
//		
//		// append the remaining segments to the existing segment list
//		for (k = 0; k < newManifest.segments.length; ++k)
//		{
//			segments.push(newManifest.segments[k]);
//		}

		// TODO: Verifiy that *this below* does what the commented section does above
		// append the remaining segments to the existing segment list
		for (k = i+1; k < newManifest.segments.size(); ++k)
		{
			segments.add(newManifest.segments.get(k));
		}
		
		// Match the new manifest's and the old manifest's DVR status
		getManifestForQuality(quality).streamEnds = newManifest.streamEnds;
		manifest.streamEnds = newManifest.streamEnds;

		updateTotalDuration();		
	}
	
	public void postRatesReady() // 
	{
		
	}
	
	public void postIndexReady()
	{
		
	}
	
	private int getWorkingQuality(int requestedQuality)
	{
		// Note that this method always returns lastQuality. It triggers a reload if it needs to, and
		// 	lastQuality will be set once the reload is complete.
		
		// If the requested quality is the same as what we're currently using, return that
		if (requestedQuality == lastQuality) return lastQuality;
		
		// If the requsted quality is the same as the target quality, we've already asked for a reload, so return the last quality
		if (requestedQuality == targetQuality) return lastQuality;
		
		// The requested quality doesn't match eithe the targetQuality or the lastQuality, which means this is new territory.
		// So we will reload the manifest for the requested quality
		targetQuality = requestedQuality;
		Log.i("StreamHandler.getWorkingQuality", "Quality Change: " + lastQuality + " --> " + requestedQuality);
		reload(targetQuality);			
		return lastQuality;
	}
	
	public ManifestSegment getFileForTime(double time, int quality)
	{
		quality = getWorkingQuality(quality);
		
		double accum = 0.0;
		Vector<ManifestSegment> segments = getSegmentsForQuality(quality);
		
		if (time < lastKnownPlaylistStartTime)
		{
			time = lastKnownPlaylistStartTime;  /// TODO: HACK Alert!!! < this should likely be handled by DVRInfo (see dash plugin index handler)
			/// No longer quite so sure this is a hack, but a requirement
			++sequenceSkips;
			Log.i("StreamHandler.getFileForTime", "SequenceSkip - time: " + time + " playlistStartTime: " + lastKnownPlaylistStartTime);
		}
		
		int i = 0;
		for (i = 0; i < segments.size(); ++i)
		{
			ManifestSegment curSegment = segments.get(i);
			
			if (curSegment.duration > time - accum)
			{
				lastSegmentIndex = i;
				ManifestSegment seg = segments.get(lastSegmentIndex);
				seg.quality = quality;
				if (altAudioManifest != null)
				{
					if (altAudioManifest.segments.size() > lastSegmentIndex)
					{
						seg.altAudioSegment = altAudioManifest.segments.get(lastSegmentIndex);
						seg.altAudioSegment.altAudioIndex = altAudioIndex;
					}
				}
				seg.key = getKeyForIndex(i);
				seg.initializeCrypto();
				return seg;
			}
			
			accum += curSegment.duration;
		}
		
		lastSegmentIndex = i;
		
		if (!getManifestForQuality(quality).streamEnds)
			stalled = true;
		
		return null;
	}
	

	public ManifestSegment getNextFile(int quality)
	{
		if (stalled)
		{
			Log.i("getNextFile()", "---- Stalled -----");
			return null;
		}
		
		quality = getWorkingQuality(quality);
		
		Vector<ManifestSegment> segments = getSegmentsForQuality( quality );
		++lastSegmentIndex;
		
		if ( lastSegmentIndex < segments.size())
		{
			ManifestSegment lastSegment = segments.get(lastSegmentIndex);
			lastSegment.key = getKeyForIndex(lastSegmentIndex);
			if (altAudioManifest != null)
			{
				if (altAudioManifest.segments.size() > lastSegmentIndex)
				{
					lastSegment.altAudioSegment = altAudioManifest.segments.get(lastSegmentIndex);
					lastSegment.altAudioSegment.altAudioIndex = altAudioIndex;
				}
			}
			if (lastSegment.startTime + lastSegment.duration < lastKnownPlaylistStartTime)
			{
				Log.i("StreamHandler.getNextFile", "SequenceSkip - startTime: " + lastSegment.startTime + " + duration: " + lastSegment.duration  + " playlistStartTime: " + lastKnownPlaylistStartTime);
				lastSegmentIndex = getSegmentIndexForTime(lastKnownPlaylistStartTime);
				++sequenceSkips;				
			}
			Log.i("StreamHandler.getNextFile", "Getting Next Segment[" + lastSegmentIndex + "]\n" + lastSegment.toString());

			lastSegment.quality = quality;
			lastSegment.initializeCrypto();
			return lastSegment;
			
		}
		
		return null;
	}
	
	//TODO: MAKE THIS WORK
	public ManifestEncryptionKey getKeyForIndex( int index )
	{
		Vector<ManifestEncryptionKey> keys = null;
		
		
		// Make sure we accessing returning the correct key list for the manifest type
		if ( manifest.type == ManifestParser.AUDIO ) keys = manifest.keys;
		else keys = getManifestForQuality( lastQuality ).keys;
		
		for ( int i = 0; i < keys.size(); i++ )
		{
			ManifestEncryptionKey key = keys.get( i );
			if ( key.startSegmentId <= index && key.endSegmentId >= index )
			{
				return key;
			}
		}
		
		return null;
	}
	
	
	// Returns duration in ms
	public int getDuration()
	{
		double accum = 0.0f;
		
		if (manifest == null) return -1;
		
		Vector<ManifestSegment> segments = getSegmentsForQuality( lastQuality );
		ManifestParser activeManifest = getManifestForQuality(lastQuality);
		int i = segments.size() - 1;
		if (i >= 0 && (activeManifest.allowCache || activeManifest.streamEnds))
		{
			accum = (segments.get(i).startTime + segments.get(i).duration) - lastKnownPlaylistStartTime;
		}
		
		return (int) (accum * 1000);
		
	}
	
	private void updateTotalDuration()
	{
		
	}
	
	private int getSegmentIndexForTime(double time)
	{
		return getSegmentIndexForTimeAndQuality(time, lastQuality);
	}
	
	private int getSegmentIndexForTimeAndQuality(double time, int quality)
	{
		if (manifest != null)
			return -1;
		
		Vector<ManifestSegment> segments = getSegmentsForQuality( lastQuality );
		
		for (int i = segments.size() - 1; i >= 0; --i)
		{
			if (segments.get(i).startTime < time)
				return i;
		}
		return 0;
	}
	
	public int getQualityLevels()
	{
		if (manifest == null) return 0;
		if (manifest.streams.size() > 0 ) return manifest.streams.size();
		return 1;
		
	}
	
	private Vector<ManifestSegment> getSegmentsForQuality(int quality)
	{
		if ( manifest == null) return new Vector<ManifestSegment>();
		if (manifest.streams.size() < 1 || manifest.streams.get(0) == null) return manifest.segments;
		else if ( quality >= manifest.streams.size() ) return manifest.streams.get(0).manifest.segments;
		else return manifest.streams.get(quality).manifest.segments;
	}
	
	public ManifestParser getManifestForQuality(int quality)
	{
		if (manifest == null) return new ManifestParser();
		if (manifest.streams.size() < 1 || manifest.streams.get(0).manifest == null) return manifest;
		else if ( quality >= manifest.streams.size() ) return manifest.streams.get(0).manifest;
		return manifest.streams.get(quality).manifest;
	}




	
	
	
	
	
	
	
	
}

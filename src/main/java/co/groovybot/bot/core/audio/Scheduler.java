package co.groovybot.bot.core.audio;

import co.groovybot.bot.util.EmbedUtil;
import co.groovybot.bot.util.SafeMessage;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import lavalink.client.player.event.AudioEventAdapterWrapped;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Message;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Log4j2
@RequiredArgsConstructor
public class Scheduler extends AudioEventAdapterWrapped {

    private static final Pattern TRACK_PATTERN = Pattern.compile("https?://.*\\.youtube\\.com/watch\\?v=([^?/&]*)");

    private final Player player;
    @Getter
    @Setter
    private boolean loopqueue = false;
    @Getter
    @Setter
    private boolean loop = false;
    @Getter
    @Setter
    private boolean shuffle = false;
    @Getter
    @Setter
    private boolean autoPlay = false;

    @Override
    public void onTrackStart(AudioPlayer audioPlayer, AudioTrack track) {
        player.announceSong(audioPlayer, track);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        handleTrackEnd(track, endReason);
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        handleTrackEnd(track, AudioTrackEndReason.LOAD_FAILED);
    }

    private void handleTrackEnd(AudioTrack track, AudioTrackEndReason reason) {
        switch (reason) {
            case FINISHED:
                Guild guild = ((MusicPlayer) player).getGuild();

                if (guild.getSelfMember().getVoiceState().getChannel() == null) return;

                AudioTrack nextTrack = null;

                // Check for Loop-mode (repeat ended song)
                if (loop) {
                    player.play(track);
                    return;
                }

                // Check for Loopqueue-mode (add track to end of queue)
                if (loopqueue) player.trackQueue.add(track);

                // Check for autoplay (only use it if queue empty)
                if (autoPlay && player.trackQueue.isEmpty()) {
                    runAutoplay(track);
                    return;
                }

                // Check for Shuffle-mode
                if (shuffle) {
                    if (player.trackQueue.isEmpty())
                        player.onEnd(true);
                    else {
                        final int index = ThreadLocalRandom.current().nextInt(player.trackQueue.size());
                        nextTrack = ((LinkedList<AudioTrack>) player.trackQueue).get(index);
                        ((LinkedList<AudioTrack>) player.trackQueue).remove(index);
                    }
                }

                // If not loop and not shuffle get next track and remove it from queue
                if (!shuffle) nextTrack = player.pollTrack();

                // Set previous-track to ended song
                if (!loopqueue) ((MusicPlayer) player).setPreviousTrack(track);

                // If no nexttrack end and leave else play nexttrack
                if (nextTrack == null) player.onEnd(true);
                else player.play(nextTrack, false);
                break;

            case LOAD_FAILED:
                player.play(player.pollTrack(), true);
                break;

            case REPLACED:
                // Check for autoplay (only use it if queue empty)
                if (autoPlay && player.trackQueue.isEmpty()) {
                    runAutoplay(track);
                    return;
                }

                break;

            default:
                break;
        }
    }

    private void runAutoplay(AudioTrack track) {
        Message infoMessage = player.announceAutoplay();

        final Matcher matcher = TRACK_PATTERN.matcher(track.getInfo().uri);

        if (!matcher.find()) {
            SafeMessage.editMessage(infoMessage, EmbedUtil.error("Not a YouTube-Track!", "We **couldn't search** for a AutoPlay-Track as the **previos** track was **not a YouTube-Track**!"));
            return;
        }

        try {
            SearchResult result = player.youtubeClient.retrieveRelatedVideos(track.getIdentifier());
            SafeMessage.editMessage(infoMessage, EmbedUtil.success("Loaded video", String.format("Successfully loaded video `%s`", result.getSnippet().getTitle())));
            queueSearchResult(result, infoMessage);
        } catch (IOException e) {
            SafeMessage.editMessage(infoMessage, EmbedUtil.error("Unknown error", "An unknown autoplay-error occurred while retrieving the next video!"));
            log.error("[Scheduler] Error while retrieving autoplay video", e);
        }
    }

    private void queueSearchResult(SearchResult result, Message infoMessage) {
        String videoId = result.getId().getVideoId();
        try {
            Video video = player.youtubeClient.getFirstVideoById(videoId);
            VideoSnippet info = video.getSnippet();
            AudioTrackInfo trackInfo = new AudioTrackInfo(info.getTitle(), info.getChannelTitle(), Duration.parse(video.getContentDetails().getDuration()).toMillis(), videoId, false, String.format("https://youtu.be/%s", videoId));
            AudioTrack track = new YoutubeAudioTrack(trackInfo, new YoutubeAudioSourceManager());
            player.play(track);
        } catch (IOException e) {
            SafeMessage.editMessage(infoMessage, EmbedUtil.error("Unknown error", "An **unknown error** occurred while **queueing** song!"));
            log.error("[AutoPlay] Error while queueing song", e);
        }
    }
}
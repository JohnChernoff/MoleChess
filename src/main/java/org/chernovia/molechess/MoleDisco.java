package org.chernovia.molechess;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import java.util.HashMap;
import java.util.Vector;

public class MoleDisco {

    class ChannelSuite {
        String title;
        Vector<Channel> openChannels = new Vector<Channel>();
        Vector<Channel> runningChannels = new Vector<Channel>();

        public ChannelSuite(String t) {
            title = t;
            generateChannels(openGames,openChannels);
        }

        public void startGame() {
            for (Channel chan : openChannels) {
                chan.delete().queue();
            }
            generateChannels(runningGames,runningChannels);
        }

        public void endGame() {
            for (Channel chan : runningChannels) {
                chan.delete().queue();
            }
        }

        void generateChannels(Category cat, Vector<Channel> list) {
            if (moleGuild != null) {
                final String wName = title + "-White", bName = title + "-Black";
                cat.createVoiceChannel(title).submit().
                        whenComplete((s, error) -> {
                            list.add(moleGuild.getVoiceChannelsByName(title, true).get(0));
                        });
                cat.createVoiceChannel(wName).submit().
                        whenComplete((s, error) -> {
                            list.add(moleGuild.getVoiceChannelsByName(wName, true).get(0));
                        });
                cat.createVoiceChannel(bName).submit().
                        whenComplete((s, error) -> {
                            if (error != null) log(error.getMessage());
                            else list.add(moleGuild.getVoiceChannelsByName(bName, true).get(0));
                        });
                cat.createTextChannel(title).submit().
                        whenComplete((s, error) -> {
                            list.add(moleGuild.getTextChannelsByName(title, true).get(0));
                        });
                cat.createTextChannel(wName).submit().
                        whenComplete((s, error) -> {
                            list.add(moleGuild.getTextChannelsByName(wName, true).get(0));
                        });
                cat.createTextChannel(bName).submit().
                        whenComplete((s, error) -> {
                            if (error != null) log(error.getMessage());
                            list.add(moleGuild.getTextChannelsByName(bName, true).get(0));
                        });
            }
        }
    }

    final JDA api;
    final long guildID = 1171672799622996018L;
    final long openGamesCatID = 1171831384747286548L;
    final long runningGamesCatID = 1171834571734659124L;
    final Guild moleGuild;
    final TextChannel moleLogChannel;
    final NewsChannel readyChannel;
    final Category openGames, runningGames;
    final HashMap<String,ChannelSuite> channels = new HashMap<String,ChannelSuite>();
    public MoleDisco(String token) {
        try {
            api = JDABuilder.createDefault(token).build().awaitReady();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        moleGuild = api.getGuildById(guildID);
        if (moleGuild == null) {
           log("Argh, cannot get guild: " + guildID);
            moleLogChannel = null;
            readyChannel = null;
            openGames = null;
            runningGames = null;
        }
        else {
            log("Home Guild: " + moleGuild.getName());
            moleLogChannel = moleGuild.getTextChannelsByName("bot",false).get(0);
            readyChannel = moleGuild.getNewsChannelsByName("ready-games",false).get(0);
            openGames = moleGuild.getCategoryById(openGamesCatID);
            runningGames = moleGuild.getCategoryById(runningGamesCatID);
            for (Channel chan : openGames.getChannels()) chan.delete().queue();
            for (Channel chan : runningGames.getChannels()) chan.delete().queue();

        }
        if (moleLogChannel != null) moleLogChannel.sendMessage("Logging in").queue();

    }

    public void newGame(String title) {
        channels.put(title,new ChannelSuite(title));
    }

    public void startGame(String title) {
        ChannelSuite chan = channels.get(title);
        if (chan != null) chan.startGame();
    }

    public void endGame(String title) {
        ChannelSuite chan = channels.get(title);
        if (chan != null) chan.endGame();
        channels.remove(title);
    }

    public void notifyReady(MoleGame game) {
        readyChannel.sendMessage(game.getTitle() + " has enough players to begin!").queue();
        String mentions = "";
        for (MolePlayer p : game.getAllPlayers()) {
            if (p.user.discoID != MoleUser.DISCO_UNKNOWN) {
                User user = api.getUserById(p.user.discoID);
                if (user != null) {
                    mentions += user.getAsMention();
                }
            }
        }
        moleLogChannel.sendMessage(mentions + " - your game is ready!");
    }

    public void log(String msg) {
        System.out.println(msg);
    }

}

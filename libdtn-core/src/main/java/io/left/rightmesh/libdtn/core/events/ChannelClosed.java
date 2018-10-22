package io.left.rightmesh.libdtn.core.events;

import io.left.rightmesh.libdtn.modules.cla.CLAChannel;

/**
 * @author Lucien Loiseau on 10/10/18.
 */
public class ChannelClosed implements DTNEvent {
    public CLAChannel channel;

    public ChannelClosed(CLAChannel channel) {
        this.channel = channel;
    }

    @Override
    public String toString() {
        return "Channel closed: local="+channel.localEID().getEIDString()+" peer="+channel.channelEID().getEIDString();
    }
}

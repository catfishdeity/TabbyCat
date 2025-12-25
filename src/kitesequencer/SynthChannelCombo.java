package kitesequencer;

import javax.sound.midi.MidiChannel;
import javax.sound.midi.Synthesizer;

class SynthChannelCombo {
	public final Synthesizer synth;
	public final int channelNumber;
	public SynthChannelCombo(Synthesizer synth, int channelNumber) {
		this.synth = synth;
		this.channelNumber = channelNumber;
	}
	public MidiChannel getChannel() {
		return synth.getChannels()[channelNumber];
	}
}
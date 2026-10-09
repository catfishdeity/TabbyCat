package tabsequencer;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import tabsequencer.config.CanvasConfig;
import tabsequencer.config.CanvasesConfig;
import tabsequencer.config.DrumCanvasConfig;
import tabsequencer.config.ProjectFileData;
import tabsequencer.config.StringCanvasConfig;
import tabsequencer.events.ControlEvent;
import tabsequencer.events.ControlEventType;
import tabsequencer.events.StickyNote;
import tabsequencer.events.ShuffleEvent;
import tabsequencer.events.TempoEvent;
import tabsequencer.events.TimeSignatureDenominator;
import tabsequencer.events.TimeSignatureEvent;

public class TabbyCat {

	public static void main(String[] args) {
		copyConfigToEtc();
		TabbyCat.getInstance();
	}

	static void copyConfigToEtc() {
		File etcDir = new File("etc");
		if (!etcDir.exists()) {
			etcDir.mkdir();
		}
		File configDir = new File("config");
		File[] configFiles = configDir.listFiles();
		if (configFiles == null) return;
		for (File src : configFiles) {
			File dest = new File(etcDir, src.getName());
			if (!dest.exists()) {
				try {
					java.nio.file.Files.copy(src.toPath(), dest.toPath());
					System.out.println("Copied " + src.getPath() + " -> " + dest.getPath());
				} catch (Exception e) {
					System.err.println("Failed to copy " + src.getName() + " to etc/: " + e.getMessage());
				}
			}
		}
	}

	private static TabbyCat instance;

	static final String loadProjectCardKey = "LOAD PROJECT";
	static final String newProjectCardKey = "NEW PROJECT";
	static final String mainInterfaceCardKey = "MAIN INTERFACE";
	static final String saveProjectCardKey = "SAVE PROJECT";
	static final String timeSignatureEventCardKey = "TIME SIGNATURE";
	static final String tempoEventCardKey = "TEMPO EVENT";
	static final String shuffleEventCardKey = "SHUFFLE EVENT";
	static final String notesEventCardKey = "NEW NOTE";;
	static final String helpCardKey = "HELP";
	static final String settingsCardKey = "SETTINGS";
	static final String audioOutputCardKey = "AUDIO OUTPUT";
	static final String instrumentSettingsCardKey = "INSTRUMENT SETTINGS EDITOR";
	
	static final double MIDDLE_C = 220.0 * Math.pow(2d, 3.0 / 12.0);
	static final int numEventRows = 3;
	static final int UI_SCALE = 2;
	double displayScale = UI_SCALE;
	static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");
	
	private ProjectFileData projectData = null;
	private CanvasesConfig canvasesConfig = CanvasesConfig.getXMLInstance();
	private Font gridFont = new Font("Monospaced", Font.BOLD, 10);
	private FontMetrics gridFontMetrics = new Canvas().getFontMetrics(gridFont);
	private Font textFont = new Font("Monospaced", Font.PLAIN, 14);
	private FontMetrics textFontMetrics = new Canvas().getFontMetrics(textFont);
	
	final int scrollTimeMargin = 4;	

	FileFilter fileFilter = new FileNameExtensionFilter(".meow files (.meow)", "meow");
	final File defaultProjectPath = new File("scores");
	final File recoveryFile = new File("recovery.meow");
	
	Map<File,Soundbank> loadedSoundbanks = new HashMap<>();
	Map<DrumCanvasConfig,Synthesizer> drumSynths = new HashMap<>();
	Map<Pair<StringCanvasConfig,Integer>,Synthesizer> stringSynths = new HashMap<>();
	volatile Mixer.Info selectedAudioMixerInfo = null;
	MidiChannel lastPreviewChannel = null;
	int lastPreviewNote = -1;
	
	final AtomicReference<File> activeFile = new AtomicReference<>(null);
	final AtomicBoolean fileHasBeenModified = new AtomicBoolean(false);
		
	final AtomicBoolean isPlaying = new AtomicBoolean(false);
	final AtomicBoolean playbackDaemonIsStarted = new AtomicBoolean(false);

	final TreeMap<Integer, Integer> cachedMeasurePositions = new TreeMap<>();
	final HashSet<Integer> cachedBeatMarkerPositions = new HashSet<>();

	final ExecutorService playbackDaemon = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "playback-daemon");
		t.setPriority(Thread.MAX_PRIORITY);
		t.setDaemon(true);
		return t;
	});
	final ScheduledExecutorService midiDaemon = Executors.newSingleThreadScheduledExecutor();

	final JFrame frame = new JFrame("TabbyCat");
		
	File defaultSoundfontFile = null;

	private TimeSignatureEventPanel timeSignatureEventPanel;
	private TempoEventPanel tempoEventPanel;
	private ShuffleEventPanel shuffleEventPanel;
	private TextInputPanel notesEventPanel;
	private LoadProjectPanel loadProjectPanel;
	private NewProjectPanel newProjectPanel;
	private MainInterfacePanel mainInterfacePanel;
	private HelpPanel helpPanel;
	private SaveProjectPanel saveProjectPanel;
	private SettingsPanel settingsPanel;
	private AudioOutputPanel audioOutputPanel;
	private InstrumentSettingsPanel instrumentSettingsPanel;
	final AtomicBoolean exitAfterSave = new AtomicBoolean(false);
	
	private CardLayout cardLayout;
	private JPanel cardPanel;

	KeyStroke k_Up = KeyStroke.getKeyStroke("UP");
	KeyStroke k_Down = KeyStroke.getKeyStroke("DOWN");
	KeyStroke k_Left = KeyStroke.getKeyStroke("LEFT");
	KeyStroke k_Right = KeyStroke.getKeyStroke("RIGHT");
	
	KeyStroke k_ShiftUp = KeyStroke.getKeyStroke("shift UP");
	KeyStroke k_ShiftDown = KeyStroke.getKeyStroke("shift DOWN");
	KeyStroke k_ShiftLeft = KeyStroke.getKeyStroke("shift LEFT");
	KeyStroke k_ShiftRight = KeyStroke.getKeyStroke("shift RIGHT");
	
	KeyStroke k_CtrlShiftUp = KeyStroke.getKeyStroke("ctrl shift UP");
	KeyStroke k_CtrlShiftDown = KeyStroke.getKeyStroke("ctrl shift DOWN");
	KeyStroke k_CtrlShiftLeft = KeyStroke.getKeyStroke("ctrl shift LEFT");
	KeyStroke k_CtrlShiftRight = KeyStroke.getKeyStroke("ctrl shift RIGHT");
	
	KeyStroke k_CtrlLeft = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "alt") + " LEFT");
	KeyStroke k_CtrlRight = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "alt") + " RIGHT");
	KeyStroke k_PlayPrevMeasure = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "alt") + " shift LEFT");
	KeyStroke k_PlayNextMeasure = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "alt") + " shift RIGHT");
	
	KeyStroke k_Enter = KeyStroke.getKeyStroke("ENTER");
	KeyStroke k_Escape = KeyStroke.getKeyStroke("ESCAPE");
	KeyStroke k_Backspace = KeyStroke.getKeyStroke("BACK_SPACE");
	KeyStroke k_Comma = KeyStroke.getKeyStroke("COMMA");
	KeyStroke k_Hyphen= KeyStroke.getKeyStroke('-');
	
	KeyStroke k_CtrlL = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " L");
	KeyStroke k_CtrlC = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " C");
	KeyStroke k_CtrlV = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " V");
	KeyStroke k_CtrlX = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " X");
	KeyStroke k_CtrlR = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " R");
	KeyStroke k_CtrlS = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " S");
	KeyStroke k_CtrlShiftS = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " shift S");
	KeyStroke k_CtrlI = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " I");
	
	KeyStroke k_Space = KeyStroke.getKeyStroke("SPACE");

	KeyStroke k_Delete   = KeyStroke.getKeyStroke("DELETE");
	KeyStroke k_Home     = KeyStroke.getKeyStroke("HOME");
	KeyStroke k_End      = KeyStroke.getKeyStroke("END");
	KeyStroke k_PageUp   = KeyStroke.getKeyStroke("PAGE_UP");
	KeyStroke k_PageDown = KeyStroke.getKeyStroke("PAGE_DOWN");

	

	void playbackDaemonFunction(long intendedFireTimeNanos) {
		// DO NOT CALL THIS ON MASTER THREAD — runs as a persistent loop
		while (true) {
			if (!isPlaying.get()) {
				LockSupport.parkNanos(1_000_000L); // 1ms nap — don't spin 100%
				continue;
			}
			// Reset if stale — e.g. after a stop/resume
			if (System.nanoTime() - intendedFireTimeNanos > 2_000_000_000L) {
				intendedFireTimeNanos = System.nanoTime();
			}
			try {
				if (projectData.getPlaybackT().get() == 0) {
					projectData.getTempo().set(projectData.getInitialTempo().get());
				}
				Future<?> midiFuture = midiDaemon.submit(() -> handleProgramEvents());
				try { midiFuture.get(); } catch (Exception e) { e.printStackTrace(); }
				SwingUtilities.invokeLater(() -> mainInterfacePanel.repaint());
			} catch (Exception e) {
				e.printStackTrace();
			}
			int tBeforeIncrement = projectData.getPlaybackT().getAndUpdate(
					i -> i + 1 == projectData.getRepeatT().get() ? projectData.getPlaybackStartT().get() : i + 1);
			double deviceScale_ = mainInterfacePanel.getGraphicsConfiguration().getDefaultTransform().getScaleX();
			int visibleCols = (int)(mainInterfacePanel.getWidth() * deviceScale_ / displayScale / mainInterfacePanel.getCellWidth());
			int cursorCol = visibleCols / 4;
			projectData.getViewT().set(Math.max(0, projectData.getPlaybackT().get() - cursorCol));
			long bpm = projectData.getTempo().get();
			long sixteenthNanos = Duration.ofMinutes(1).dividedBy(bpm).dividedBy(4).toNanos();
			double shuffleRatio = projectData.getShuffle().get() / 100.0;
			long thisSixteenthNanos = tBeforeIncrement % 2 == 0
					? (long)(sixteenthNanos * (1.0 + shuffleRatio))
					: (long)(sixteenthNanos * (1.0 - shuffleRatio));
			long nextIntendedNanos = intendedFireTimeNanos + thisSixteenthNanos;
			// Hybrid: park until 2ms before target, then spin for sub-ms precision
			long sleepNanos = nextIntendedNanos - System.nanoTime() - 2_000_000L;
			if (sleepNanos > 0) {
				LockSupport.parkNanos(sleepNanos);
			}
			while (System.nanoTime() < nextIntendedNanos) { /* spin */ }
			intendedFireTimeNanos = nextIntendedNanos;
		}
	}	
	
	Map<MidiChannel,Integer> activeDrumNotes = new 
			HashMap<>();
	Map<MidiChannel,Integer> activeStringNotes = new HashMap<>();
	void handleProgramEvents() {
		int t = projectData.getPlaybackT().get();
		
		projectData.getEventData().entrySet().stream() 
		.filter(e->e.getKey().x == t).map(e->e.getValue())
		.forEach(a -> {
			switch (a.getType()) {			
			case TEMPO:
				projectData.getTempo().set(((TempoEvent) a).getTempo());
				break;
			case SHUFFLE:
				projectData.getShuffle().set(((ShuffleEvent) a).getShuffle());
				break;
			default:
				break;
			}
		});
		activeDrumNotes.forEach((midiChannel,midiNoteNum) -> {
			midiChannel.noteOff(midiNoteNum);
		});
		for (CanvasConfig canvas :projectData.getCanvases().getCanvases()) {
			if (canvas instanceof StringCanvasConfig) {
				StringCanvasConfig stringCanvas = (StringCanvasConfig) canvas; 
				for (int row = 0; row < stringCanvas.getRowCount(); row++) {
					InstrumentDataKey key = new InstrumentDataKey(stringCanvas.getName(),t,row);
					String val = projectData.getInstrumentData().get(key);
					int channelNum = row%15 >= 9 ? row%15+1:row%15;
					try {
						Synthesizer synth = getSynth(stringCanvas,row);
						MidiChannel channel = synth.getChannels()[channelNum];
						if (activeStringNotes.containsKey(channel)) {
							if (val == null || val.charAt(0) != '-') {
								channel.noteOff(activeStringNotes.get(channel));
								activeStringNotes.remove(channel);
							}
						}
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			}
		}
		projectData.getInstrumentData().entrySet().stream()
		.filter(e->e.getKey().getTime() == t)
		.forEach(e -> {
			InstrumentDataKey dataKey = e.getKey();
			String value = e.getValue();
			projectData.getCanvasConfig(dataKey.getInstrumentName()).ifPresent(canvasConfig -> {
				if (canvasConfig instanceof DrumCanvasConfig) {
					DrumCanvasConfig drumConfig = (DrumCanvasConfig) canvasConfig;
					drumConfig.getMidiNumber(value).ifPresent(midiNumber -> {
						try {
							Synthesizer synth = getSynth(drumConfig);
							synth.getChannels()[9].noteOn(midiNumber, 100);
							activeDrumNotes.put(synth.getChannels()[9],midiNumber);
						} catch (Exception e1) {
							e1.printStackTrace();
						}
					});
				} else {
					StringCanvasConfig guitarConfig = (StringCanvasConfig) canvasConfig;
					if (value.charAt(0) != '-') {

						guitarConfig.getFrequency(value,dataKey.getRow()).ifPresent(freq -> {
							try {
								
								int channelNum = dataKey.getRow()%15 >= 9 ?
										dataKey.getRow()%15+1:dataKey.getRow()%15;
								
								
								Synthesizer synth = getSynth(guitarConfig,dataKey.getRow());
								MidiChannel channel = synth.getChannels()[channelNum];
								
								double n = (12 * Math.log(freq/440)/Math.log(2) + 69);
								int midiNote = (int) Math.round(n);
								double semitoneOffset = n - midiNote;
								final double semitoneRange = 2.0;
								double bendRatio = semitoneOffset/ semitoneRange;
								int pitchBend = 8192 + (int) (bendRatio*8192);
								pitchBend = Math.max(0, Math.min(16383, pitchBend));
								int lsb = pitchBend & 0x7F;
								int msb = (pitchBend >> 7) & 0x7F;
								try {
									ShortMessage pb = new ShortMessage();					
									pb.setMessage(ShortMessage.PITCH_BEND, channelNum, lsb, msb);
									synth.getReceiver().send(pb, -1);
									channel.noteOn(midiNote, 100);
									activeStringNotes.put(channel,midiNote);
								} catch (Exception ex) {
									ex.printStackTrace();
								}
							} catch (Exception ex) {
								ex.printStackTrace();
							}							
						});
					} 
				}
			});
		});
	}
	
	static List<Mixer.Info> getOutputMixerInfos() {
		List<Mixer.Info> result = new ArrayList<>();
		AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
		DataLine.Info dlInfo = new DataLine.Info(SourceDataLine.class, fmt);
		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			try {
				if (AudioSystem.getMixer(info).isLineSupported(dlInfo))
					result.add(info);
			} catch (Exception ignored) {}
		}
		return result;
	}

	Synthesizer openNewSynth() throws Exception {
		Mixer.Info mixerInfo = selectedAudioMixerInfo;
		if (mixerInfo != null)
			System.setProperty("javax.sound.sampled.SourceDataLine", "#" + mixerInfo.getName());
		try {
			Synthesizer synth = MidiSystem.getSynthesizer();
			synth.open();
			return synth;
		} finally {
			System.clearProperty("javax.sound.sampled.SourceDataLine");
		}
	}

	Synthesizer getSynth(StringCanvasConfig config, int row) throws Exception {
		
		Pair<StringCanvasConfig,Integer> key = new Pair<>(config,row);
		if (stringSynths.containsKey(key)) {
			return stringSynths.get(key);
		} else {
			Synthesizer synth = openNewSynth();
			if (config.getSoundfontFile().isPresent()) {
				File file = config.getSoundfontFile().get();
				Soundbank soundbank;
				if (loadedSoundbanks.containsKey(file)) {
					soundbank = loadedSoundbanks.get(file);
				} else {
					soundbank = MidiSystem.getSoundbank(file);
					loadedSoundbanks.put(file, soundbank);
				}
				for (Instrument instrument : soundbank.getInstruments()) {
					if (instrument.getPatch().getBank() ==
							config.getBank() &&
							instrument.getPatch().getProgram() ==
							config.getProgram()) {
						synth.loadInstrument(instrument);
						for (int i : new int[] {0,1,2,3,4,5,6,7,8,10,11,12,13,14,15}) {
							synth.getChannels()[i].programChange(config.getBank(),config.getProgram());
						}
					}
				}
			}
			stringSynths.put(key,synth);
			return synth;
		}
	}

	Synthesizer getSynth(DrumCanvasConfig config) throws Exception {
		if (drumSynths.containsKey(config)) {
			return drumSynths.get(config);
		} else {
			Synthesizer synth = openNewSynth();
			if (config.getSoundfontFile().isPresent()) {
				File file = config.getSoundfontFile().get();
				Soundbank soundbank;
				if (loadedSoundbanks.containsKey(file)) {
					soundbank = loadedSoundbanks.get(file);
				} else {
					soundbank = MidiSystem.getSoundbank(file);
					loadedSoundbanks.put(file, soundbank);
				}
				for (Instrument instrument : soundbank.getInstruments()) {
					if (instrument.getPatch().getBank() ==
							config.getBank() &&
							instrument.getPatch().getProgram() ==
							config.getProgram()) {
						synth.loadInstrument(instrument);
						synth.getChannels()[9].programChange(config.getBank(),config.getProgram());
					}
				}
			}

			drumSynths.put(config,synth);
			return synth;
		}
	}


	void updateWindowTitle() {
		StringBuilder sb = new StringBuilder("TabbyCat");
		if (projectData != null) {
			sb.append(" (");
			if (activeFile.get() != null) {
				String name = activeFile.get().getName();
				if (name.endsWith(".meow")) {
					name = name.substring(0, name.length() - 5);
				}
				sb.append(name);
			} else {
				sb.append("untitled");
			}
			if (fileHasBeenModified.get()) {
				sb.append(" *");
			}
			sb.append(")");
		}
		frame.setTitle(sb.toString());
	}
	

	void togglePlayStatus() {
		if (isPlaying.get()) {
			stopPlayback();
		} else {
			startPlayback();
		}
	}
	

	long lastStopTimeMs = 0;

	void startPlayback() {
		if (System.currentTimeMillis() - lastStopTimeMs > 30_000) {
			flushSynths();
		}
		if (!playbackDaemonIsStarted.get()) {
			playbackDaemonIsStarted.set(true);
			playbackDaemon.submit(() -> playbackDaemonFunction(System.nanoTime()));
		}
		isPlaying.set(true);
	}

	void stopPlayback() {
		isPlaying.set(false);
		lastStopTimeMs = System.currentTimeMillis();
		activeDrumNotes.forEach((channel, note) -> channel.noteOff(note));
		activeDrumNotes.clear();
		activeStringNotes.forEach((channel, note) -> channel.noteOff(note));
		activeStringNotes.clear();
	}

	void flushSynths() {
		Mixer.Info mixerInfo = selectedAudioMixerInfo;
		if (mixerInfo != null)
			System.setProperty("javax.sound.sampled.SourceDataLine", "#" + mixerInfo.getName());
		try {
			stringSynths.forEach((key, synth) -> {
				try {
					synth.close();
					synth.open();
					StringCanvasConfig config = key.a;
					if (config.getSoundfontFile().isPresent()) {
						Soundbank soundbank = loadedSoundbanks.get(config.getSoundfontFile().get());
						if (soundbank != null) {
							for (Instrument instrument : soundbank.getInstruments()) {
								if (instrument.getPatch().getBank() == config.getBank() &&
										instrument.getPatch().getProgram() == config.getProgram()) {
									synth.loadInstrument(instrument);
									for (int i : new int[]{0,1,2,3,4,5,6,7,8,10,11,12,13,14,15}) {
										synth.getChannels()[i].programChange(config.getBank(), config.getProgram());
									}
								}
							}
						}
					}
				} catch (Exception ignored) {}
			});
			drumSynths.forEach((config, synth) -> {
				try {
					synth.close();
					synth.open();
					if (config.getSoundfontFile().isPresent()) {
						Soundbank soundbank = loadedSoundbanks.get(config.getSoundfontFile().get());
						if (soundbank != null) {
							for (Instrument instrument : soundbank.getInstruments()) {
								if (instrument.getPatch().getBank() == config.getBank() &&
										instrument.getPatch().getProgram() == config.getProgram()) {
									synth.loadInstrument(instrument);
									synth.getChannels()[9].programChange(config.getBank(), config.getProgram());
								}
							}
						}
					}
				} catch (Exception ignored) {}
			});
		} finally {
			System.clearProperty("javax.sound.sampled.SourceDataLine");
		}
	}

	void resetSynths() {
		stopPlayback();
		stringSynths.forEach((key, synth) -> { try { synth.close(); } catch (Exception ignored) {} });
		stringSynths.clear();
		drumSynths.forEach((config, synth) -> { try { synth.close(); } catch (Exception ignored) {} });
		drumSynths.clear();
	}

	void playPreviewNote(CanvasConfig canvasConfig, String token, int row) {
		if (lastPreviewChannel != null && lastPreviewNote >= 0) {
			lastPreviewChannel.noteOff(lastPreviewNote);
			lastPreviewChannel = null;
			lastPreviewNote = -1;
		}
		try {
			if (canvasConfig instanceof StringCanvasConfig) {
				StringCanvasConfig stringCanvas = (StringCanvasConfig) canvasConfig;
				stringCanvas.getFrequency(token, row).ifPresent(freq -> {
					try {
						int channelNum = row % 15 >= 9 ? row % 15 + 1 : row % 15;
						Synthesizer synth = getSynth(stringCanvas, row);
						MidiChannel channel = synth.getChannels()[channelNum];
						double n = 12 * Math.log(freq / 440.0) / Math.log(2) + 69;
						int midiNote = (int) Math.round(n);
						double bendRatio = (n - midiNote) / 2.0;
						int pitchBend = Math.max(0, Math.min(16383, 8192 + (int)(bendRatio * 8192)));
						ShortMessage pb = new ShortMessage();
						pb.setMessage(ShortMessage.PITCH_BEND, channelNum, pitchBend & 0x7F, (pitchBend >> 7) & 0x7F);
						synth.getReceiver().send(pb, -1);
						channel.noteOn(midiNote, 90);
						lastPreviewChannel = channel;
						lastPreviewNote = midiNote;
						Thread t = new Thread(() -> {
							try { Thread.sleep(350); } catch (InterruptedException ignored) {}
							channel.noteOff(midiNote);
						});
						t.setDaemon(true);
						t.start();
					} catch (Exception ex) { ex.printStackTrace(); }
				});
			} else if (canvasConfig instanceof DrumCanvasConfig) {
				DrumCanvasConfig drumConfig = (DrumCanvasConfig) canvasConfig;
				drumConfig.getMidiNumber(token).ifPresent(midiNumber -> {
					try {
						Synthesizer synth = getSynth(drumConfig);
						MidiChannel channel = synth.getChannels()[9];
						channel.noteOn(midiNumber, 90);
						lastPreviewChannel = channel;
						lastPreviewNote = midiNumber;
						Thread t = new Thread(() -> {
							try { Thread.sleep(350); } catch (InterruptedException ignored) {}
							channel.noteOff(midiNumber);
						});
						t.setDaemon(true);
						t.start();
					} catch (Exception ex) { ex.printStackTrace(); }
				});
			}
		} catch (Exception ex) { ex.printStackTrace(); }
	}
	
	public void updateMeasureLinePositions() {
		AtomicReference<TimeSignatureEvent> timeSignature = new AtomicReference<>(
				new TimeSignatureEvent(4, TimeSignatureDenominator._4));
		AtomicInteger counter = new AtomicInteger(0);
		AtomicInteger measure = new AtomicInteger(1);
		Map<Integer, Integer> measures = new TreeMap<>();
		Set<Integer> markers = new HashSet<>();
		measures.put(0, 1);
		measure.set(2);
		for (int t = 0; t < 1000 * 16; t++) {
			int t_ = t;
			Optional<TimeSignatureEvent> tsO = IntStream.range(0, 3)
					.mapToObj(row -> new Point(t_, row)).filter(a -> projectData.getEventData().containsKey(a))
					.map(projectData.getEventData()::get).filter(a -> a.getType() == ControlEventType.TIME_SIGNATURE)
					.map(a -> (TimeSignatureEvent) a).findFirst();

			if (tsO.isPresent()) {
				timeSignature.set(tsO.get());
				counter.set(0);
				if (t > 0) {
					measures.put(t, measure.getAndIncrement());
				}
			} else {
				if (counter.get() == timeSignature.get().get16ths()) {
					counter.set(0);
					measures.put(t, measure.getAndIncrement());
				}
			}
			if (counter.get() > 0 && counter.get() % (16 / timeSignature.get().denominator.getValue()) == 0) {
				markers.add(t);
			}
			counter.incrementAndGet();
		}
		cachedMeasurePositions.clear();
		cachedBeatMarkerPositions.clear();
		cachedMeasurePositions.putAll(measures);
		cachedBeatMarkerPositions.addAll(markers);
	}


	public static TabbyCat getInstance() {
		if (instance == null) {
			instance = new TabbyCat();
		}
		return instance;
	}

	private TabbyCat() {
		createGui();
	}
	
	void createGui() {
		
		cardLayout = new CardLayout();
		cardPanel = new JPanel(cardLayout);
		
		loadProjectPanel = new LoadProjectPanel();
		cardPanel.add(loadProjectPanel,loadProjectCardKey);
		newProjectPanel = new NewProjectPanel();
		cardPanel.add(newProjectPanel,newProjectCardKey);
		mainInterfacePanel = new MainInterfacePanel();
		cardPanel.add(mainInterfacePanel,mainInterfaceCardKey);
		saveProjectPanel = new SaveProjectPanel();
		cardPanel.add(saveProjectPanel,saveProjectCardKey);
		timeSignatureEventPanel = new TimeSignatureEventPanel();
		cardPanel.add(timeSignatureEventPanel,timeSignatureEventCardKey);
		tempoEventPanel = new TempoEventPanel();
		cardPanel.add(tempoEventPanel,tempoEventCardKey);
		shuffleEventPanel = new ShuffleEventPanel();
		cardPanel.add(shuffleEventPanel, shuffleEventCardKey);
		notesEventPanel = new TextInputPanel(
			"Add note:",
			text -> {
				projectData.getEventData().put(
					new Point(projectData.getCursorT().get(), projectData.getSelectedRow().get()),
					new StickyNote(text));
				cardLayout.show(cardPanel, mainInterfaceCardKey);
			},
			() -> cardLayout.show(cardPanel, mainInterfaceCardKey)
		);
		cardPanel.add(notesEventPanel,notesEventCardKey);
		helpPanel = new HelpPanel();
		cardPanel.add(helpPanel,helpCardKey);
		settingsPanel = new SettingsPanel();
		cardPanel.add(settingsPanel,settingsCardKey);
		audioOutputPanel = new AudioOutputPanel();
		cardPanel.add(audioOutputPanel,audioOutputCardKey);
		instrumentSettingsPanel = new InstrumentSettingsPanel();
		cardPanel.add(instrumentSettingsPanel,instrumentSettingsCardKey);
		frame.getContentPane().add(cardPanel,BorderLayout.CENTER);
		frame.pack();
		Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
				.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
		frame.setSize(new Dimension(
				(int) Math.min(1000, screenBounds.getWidth()),
				(int) Math.min(500, screenBounds.getHeight())));

		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent e) {
				System.exit(0);
			}
		});

		Runtime.getRuntime().addShutdownHook(new Thread(this::saveRecovery));

		if (recoveryFile.exists()) {
			try {
				Element root = loadXML(recoveryFile);
				String afPath = root.getAttribute("activeFilePath");
				if (afPath != null && !afPath.isEmpty()) {
					activeFile.set(new File(afPath));
				}
				String wasModified = root.getAttribute("wasModified");
				fileHasBeenModified.set("true".equals(wasModified));
				updateWindowTitle();
				cardLayout.show(cardPanel, mainInterfaceCardKey);
				Rectangle lastProjScreenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
						.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
				frame.pack();
				frame.setSize(new Dimension(
						(int) lastProjScreenBounds.getWidth(),
						(int) Math.min(lastProjScreenBounds.getHeight(), mainInterfacePanel.computeNeededHeight())));
			} catch (Exception ignored) {}
		}
		if (projectData == null) {
			cardLayout.show(cardPanel, newProjectCardKey);
		}

		frame.setVisible(true);
	}
	

	Element loadXML(File file) throws Exception {
		SchemaFactory schemaF = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
		Schema schema = schemaF.newSchema(new File("schemas/projectFiles.xsd"));
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		dbf.setSchema(schema);
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(file);
		Element root = doc.getDocumentElement();
		ProjectFileData projectFileData = ProjectFileData.fromXMLElement(root);
		this.projectData = projectFileData;
		if (projectFileData.getUiScale() > 0) {
			displayScale = projectFileData.getUiScale();
			settingsPanel.uiScaleValue = displayScale;
		}
		updateMeasureLinePositions();
		for (CanvasConfig canvasConfig : projectData.getCanvases().getCanvases()) {
			if (canvasConfig instanceof StringCanvasConfig) {
				StringCanvasConfig a = (StringCanvasConfig) canvasConfig;
				for (int row = 0; row < a.getRowCount(); row++) {
					getSynth(a, row);
				}
			} else {
				getSynth((DrumCanvasConfig) canvasConfig);
			}
		}
		return root;
	}

	void saveXML(File file) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.newDocument();
		Element root = projectData.toXMLElement(doc);
		doc.appendChild(root);
		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer t = tf.newTransformer();
		t.setOutputProperty(OutputKeys.INDENT, "yes");
		t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
		t.transform(new DOMSource(doc), new StreamResult(file));
	}

	void saveRecovery() {
		if (projectData == null) return;
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.newDocument();
			Element root = projectData.toXMLElement(doc);
			File af = activeFile.get();
			if (af != null) root.setAttribute("activeFilePath", af.getAbsolutePath());
			root.setAttribute("wasModified", Boolean.toString(fileHasBeenModified.get()));
			doc.appendChild(root);
			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer t = tf.newTransformer();
			t.setOutputProperty(OutputKeys.INDENT, "yes");
			t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
			t.transform(new DOMSource(doc), new StreamResult(recoveryFile));
		} catch (Exception ignored) {}
	}
	AbstractAction rToA(Runnable r) {
		return new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {			
				r.run();
			}			
		};
	}	
	
	class NewProjectPanel extends JPanel {
		
		StringBuffer songName = new StringBuffer("New Song");
		StringBuffer artistName = new StringBuffer("Artist");
		int selectedIndex = 0;
		
		final int modulo = 1;
		public Map<CanvasConfig,Integer> indexMap = new HashMap<>();
		public NewProjectPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape,"esc");
			actionMap.put("esc", rToA(()->{ if (projectData != null) cardLayout.show(cardPanel, mainInterfaceCardKey); }));
			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
			inputMap.put(k_Backspace,"backspace");
			actionMap.put("backspace", rToA(this::backspace));
			
			for (char c = 'A'; c <= 'Z'; c++) {
				String upper = String.valueOf(c);				
				char lower = upper.toLowerCase().charAt(0);;
				KeyStroke withoutShiftKey= KeyStroke.getKeyStroke(upper);
				KeyStroke withShiftKey = KeyStroke.getKeyStroke("shift "+upper);
				inputMap.put(withoutShiftKey, lower+"");
				inputMap.put(withShiftKey, upper);
				char c_ = c;
				actionMap.put(lower+"", rToA(()->handleChar(lower)));
				actionMap.put(upper, rToA(()->handleChar(c_)));				
				
			}
			for (char c = '0'; c <= '9'; c++) {
				KeyStroke key = KeyStroke.getKeyStroke(c);
				inputMap.put(key, String.valueOf(c));
				char c_ = c;
				actionMap.put(String.valueOf(c),rToA(()->handleChar(c_)));
			}
			inputMap.put(k_Space, "space");
			actionMap.put("space", rToA(() -> {
				if (selectedIndex == 0) { songName.append(' '); repaint(); }
				else if (selectedIndex == 1) { artistName.append(' '); repaint(); }
			}));
		}

		void handleChar(char c) {
			
			if (selectedIndex == 0) {
				songName.append(c);
			} else if (selectedIndex == 1) {
				artistName.append(c);
			} else if (selectedIndex == canvasesConfig.getCanvases().size()+2) {
				//do nothing
				//System.out.println("hey");
			} else {
				if (c >= '0' && c <= '9') {
					CanvasConfig canvas = canvasesConfig.getCanvases().get(selectedIndex-2);
					String a = indexMap.containsKey(canvas)?indexMap.get(canvas).toString():"";
					String b = a+c;
					indexMap.put(canvas, Integer.parseInt(b));
					//indexIndices(canvas);
				}
			}
			repaint();
		}
			
		void indexIndices(CanvasConfig canvas) {
			
			Comparator<Pair<CanvasConfig,Integer>> cmp1 = Comparator.comparing(p->p.b);
			Comparator<Pair<CanvasConfig,Integer>> cmp2 = Comparator.comparing(p->p.a == canvas);
			
			List<Pair<CanvasConfig,Integer>> sortedIndices =
					indexMap.entrySet().stream().map(entry -> {
						return new Pair<>(entry.getKey(),entry.getKey() != canvas && entry.getValue() >= indexMap.getOrDefault(canvas, 0)
								?entry.getValue()+1:entry.getValue());
					}).sorted(cmp2.reversed().thenComparing(cmp1)).collect(Collectors.toList());
			
			int i = 1;
			indexMap.clear();
			for (Pair<CanvasConfig,Integer> p : sortedIndices) {
				indexMap.put(p.a, i++);
			}
			
			//
		}
		
		void backspace() {
			if (selectedIndex == 0 && songName.length() > 0) {
				songName.deleteCharAt(songName.length()-1);				
			} else if (selectedIndex == 1 && artistName.length() > 0) {
				artistName.deleteCharAt(artistName.length()-1);
			} else if (selectedIndex == canvasesConfig.getCanvases().size()+2) {
				//do nothing
			} else {
				CanvasConfig canvas = canvasesConfig.getCanvases().get(selectedIndex-2);
				String a = indexMap.containsKey(canvas)?indexMap.get(canvas).toString():"";
				String b = a.length() == 0 ? "" : a.substring(0,a.length()-1);
				if (b.isEmpty()) {
					indexMap.remove(canvas);
					
				}  else {
					indexMap.put(canvas, Integer.parseInt(b));
				}
			}
			repaint();
		}
		
		void down() {
			selectedIndex++;
			
			if (selectedIndex==3+canvasesConfig.getCanvases().size()) {
				selectedIndex=0;
			}
			repaint();
		}
		
		void up() {
			selectedIndex--;
			if (selectedIndex==-1) {
				selectedIndex=2+canvasesConfig.getCanvases().size();
			}
			repaint();
		}
		
		void enter() {
			if (selectedIndex == canvasesConfig.getCanvases().size()+2 && !indexMap.isEmpty()) {
				Comparator<Pair<CanvasConfig,Integer>> cmp1 = Comparator.comparing(p->p.b);
				Comparator<Pair<CanvasConfig,Integer>> cmp2 = Comparator.comparing(p->p.a.getName());

				CanvasesConfig config = new CanvasesConfig(
							indexMap.entrySet().stream().map(a->new Pair<>(a.getKey(),a.getValue()))
							.sorted(cmp1.thenComparing(cmp2)).map(a->a.a).collect(Collectors.toList()));
				projectData = new ProjectFileData(config);
				projectData.setSongName(songName.toString());
				projectData.setArtistName(artistName.toString());
				projectData.setUiScale(displayScale);
				activeFile.set(null);
				fileHasBeenModified.set(false);
				updateWindowTitle();
				cardLayout.show(cardPanel, mainInterfaceCardKey);
				updateMeasureLinePositions();
			}
		}
		

		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);			
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
			int y = textFontMetrics.getMaxAscent();
			int rowHeight = textFontMetrics.getMaxAscent();
			String titleLabel = "Title:";
			String artistLabel = "Artist:";
			int textFieldX = Stream.of(titleLabel,artistLabel).mapToInt(a->(int) textFontMetrics.stringWidth(a)).max().getAsInt();
			int textFieldWidth = textFontMetrics.stringWidth("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
			g.setPaint(Color.WHITE);
			g.drawString(titleLabel,2,y);
			g.setPaint(selectedIndex==0?Color.LIGHT_GRAY:Color.GRAY);
			g.fillRect(textFieldX,y-rowHeight,textFieldWidth,rowHeight);
			g.setPaint(Color.WHITE);
			g.setClip(new Rectangle2D.Double(textFieldX,y-rowHeight,textFieldWidth,rowHeight));
			g.drawString(songName.toString() + (selectedIndex == 0 ? "|" : ""),textFieldX,y);
			g.setClip(null);
			y+=rowHeight;
			g.setPaint(Color.WHITE);
			g.drawString(artistLabel,2,y);
			g.setPaint(selectedIndex==1?Color.LIGHT_GRAY:Color.GRAY);
			g.fillRect(textFieldX,y-rowHeight,textFieldWidth,rowHeight);
			g.setPaint(Color.WHITE);
			g.setClip(new Rectangle2D.Double(textFieldX,y-rowHeight,textFieldWidth,rowHeight));
			g.drawString(artistName.toString() + (selectedIndex == 1 ? "|" : ""),textFieldX,y);
			g.setClip(null);
			y+=rowHeight;
			
			int yForInstruments = y;
			int xForInstruments = 2;
			
			Map<Integer, List<Pair<Integer, CanvasConfig>>> groupedByModulo = 
					IntStream.range(0, canvasesConfig.getCanvases().size()).mapToObj(i->new Pair<>(i,canvasesConfig.getCanvases().get(i)))
					.collect(Collectors.groupingBy(a->a.a%modulo));
			
			for (int i : groupedByModulo.keySet()) {
				
				int w = groupedByModulo.get(i).stream()
						.mapToInt(a->textFontMetrics.stringWidth(a.b.getName())).max().orElse(0)+rowHeight+20;
				
				for (Pair<Integer,CanvasConfig> p : groupedByModulo.get(i)) {
					g.setPaint(Color.white);
					g.drawRect(xForInstruments, yForInstruments-rowHeight,rowHeight, rowHeight);
					g.setPaint(p.a.intValue() == selectedIndex-2?Color.GRAY:Color.black);
					g.fillRect(xForInstruments+rowHeight,yForInstruments-rowHeight,w-rowHeight,rowHeight);
					g.setPaint(Color.WHITE);
					if (indexMap.containsKey(p.b)) {
						g.drawString(indexMap.get(p.b)+"",xForInstruments,yForInstruments);
					}
					g.drawString(p.b.getName(), rowHeight+2+xForInstruments,yForInstruments);
					yForInstruments+=rowHeight;
				}
				xForInstruments+=w;
				yForInstruments = y;
			}
			y+=rowHeight*groupedByModulo.values().stream().mapToInt(a->a.size()).max().orElse(1);
			
			g.setPaint(selectedIndex == canvasesConfig.getCanvases().size()+2?Color.GRAY:Color.black);
			g.fillRect(0,y-rowHeight,textFontMetrics.stringWidth("LOAD"),rowHeight);
			g.setPaint(selectedIndex == canvasesConfig.getCanvases().size()+2?new Color(255,255,150):Color.WHITE);
			g.drawString("LOAD", 2,y);
		}
	}
	
	class LoadProjectPanel extends JPanel {
		private File workingDir = defaultProjectPath;
		private int selectedIndex = 0;
		public LoadProjectPanel() {
			this.workingDir = defaultProjectPath;
			if (!workingDir.exists()) {
				workingDir.mkdir();
			}
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape,"esc");
			actionMap.put("esc", rToA(()->{ if (projectData != null) cardLayout.show(cardPanel, mainInterfaceCardKey); }));
			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));

		}
		
		private void up() {
			int numFiles = 0;
			for (File f : workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					numFiles++;
				}
			}

			if (selectedIndex==0) {
				selectedIndex = numFiles;
			} else {
				selectedIndex-=1;
			}
			repaint();
		}

		private void down() {
			int numFiles = 0;
			for (File f : workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					numFiles++;
				}
			}
			selectedIndex+=1;
			if (selectedIndex == 1+numFiles) {
				selectedIndex = 0;
			}
			repaint();

		}

		private void enter() {
			if (selectedIndex == 0) {
				this.workingDir = new File(workingDir.getAbsolutePath()).getParentFile();
				repaint();
			} else {
				List<File> files =
						Arrays.asList(workingDir.listFiles()).stream().filter(a->a.isDirectory() || fileFilter.accept(a))
						.collect(java.util.stream.Collectors.toList());
				if (files.get(selectedIndex-1).isDirectory()) {
					workingDir = new File(workingDir.getAbsolutePath()+"/"+files.get(selectedIndex-1).getName());
					selectedIndex = 0;
					repaint();
				} else {

					try {
						loadXML(files.get(selectedIndex-1));
						activeFile.set(files.get(selectedIndex-1));
						fileHasBeenModified.set(false);
						updateWindowTitle();
					} catch (Exception e) {

						e.printStackTrace();
					} finally {
						cardLayout.show(cardPanel, mainInterfaceCardKey);
						Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
								.getDefaultScreenDevice().getDefaultConfiguration().getBounds();
						frame.setSize(new Dimension(
								(int) screenBounds.getWidth(),
								(int) Math.min(screenBounds.getHeight(), mainInterfacePanel.computeNeededHeight())));
					}
				}

			}
		}
		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
			int y = textFontMetrics.getMaxAscent();
			g.setPaint(Color.RED);
			g.drawString(workingDir.getAbsolutePath(),getWidth()-textFontMetrics.stringWidth(workingDir.getAbsolutePath())-2, y);

			List<Pair<String,Color>> strings= new ArrayList<>();
			strings.add(new Pair<>("..",new Color(255,255,180)));
			if (workingDir == null) {
				return;
			}
			for (File f : this.workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					strings.add(new Pair<>(f.getName(),f.isDirectory()?new Color(255,255,180):new Color(180,255,180)));
				}
			
			}
			int w = strings.stream().mapToInt(a->textFontMetrics.stringWidth(a.a)).max().getAsInt();
			for (int i = 0; i < strings.size(); i++) {
				Pair<String,Color> p = strings.get(i);
				g.setPaint(selectedIndex == i?Color.DARK_GRAY:Color.black);
				g.fillRect(0, y-textFontMetrics.getMaxAscent(), w, textFontMetrics.getMaxAscent());
				g.setPaint(p.b);
				g.drawString(p.a, 2, y);
				y+=textFontMetrics.getMaxAscent();
			}
			
						
		}
	}
	

	enum SequencePosition {
		NEW, OPEN, SAVE, SAVE_AS, TEMPO, SHUFFLE, TAPPER, SETTINGS, HELP;
	}

	enum CardinalDirection {
		RIGHT, LEFT, UP, DOWN,
		SHIFT_RIGHT, SHIFT_LEFT, SHIFT_UP, SHIFT_DOWN,
		CTRL_RIGHT, CTRL_LEFT,
		CTRL_SHIFT_LEFT, CTRL_SHIFT_RIGHT, CTRL_SHIFT_UP, CTRL_SHIFT_DOWN;
	}

	class MainInterfacePanel extends JPanel {

		SequencePosition sequencePosition = SequencePosition.TAPPER;		
		boolean isInGrid = false;
		AtomicBoolean isSelectionMode = new AtomicBoolean(false);
		
		final Map<InstrumentDataKey, String> instrumentClipboard = new HashMap<>();
		final Map<Point, ControlEvent> eventClipboard = new HashMap<>();
		
		int lassoCanvasNumber = -1;
		int lassoT0 = -1;
		int lassoRow0 = -1;
		boolean isMouseLasso = false;

		double horizScrollAccum = 0.0;
		double vertScrollAccum  = 0.0;
		int viewY = 0;

		List<Shape> lastCanvasGrids = new ArrayList<>();
		int lastVerticalTranslate = 0;
		int lastCellWidth = 1;
		int lastRowHeight = 1;
		Map<SequencePosition, Rectangle2D> menuItemBounds = new EnumMap<>(SequencePosition.class);
		int lastTopBarHeight = 0;
		
		public MainInterfacePanel() {
			this.setFocusTraversalKeysEnabled(false);
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();

			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Left,"left");
			actionMap.put("left", rToA(this::left));
			inputMap.put(k_Right,"right");
			actionMap.put("right", rToA(this::right));
			
			inputMap.put(k_ShiftUp,"shiftup");
			actionMap.put("shiftup", rToA(this::shiftUp));
			inputMap.put(k_ShiftDown,"shiftdown");
			actionMap.put("shiftdown", rToA(this::shiftDown));
			inputMap.put(k_ShiftLeft,"shiftleft");
			actionMap.put("shiftleft", rToA(this::shiftLeft));
			inputMap.put(k_ShiftRight,"shiftright");
			actionMap.put("shiftright", rToA(this::shiftRight));
			
			inputMap.put(k_CtrlLeft,"ctrlleft");
			actionMap.put("ctrlleft", rToA(this::ctrlLeft));
			inputMap.put(k_CtrlRight,"ctrlright");
			actionMap.put("ctrlright", rToA(this::ctrlRight));			
			
			inputMap.put(k_CtrlShiftUp,"ctrlshiftup");
			actionMap.put("ctrlshiftup", rToA(this::ctrlShiftUp));
			inputMap.put(k_CtrlShiftDown,"ctrlshiftdown");
			actionMap.put("ctrlshiftdown", rToA(this::ctrlShiftDown));
			inputMap.put(k_CtrlShiftLeft,"ctrlshiftleft");
			actionMap.put("ctrlshiftleft", rToA(this::ctrlShiftLeft));
			inputMap.put(k_CtrlShiftRight,"ctrlshiftright");
			actionMap.put("ctrlshiftright", rToA(this::ctrlShiftRight));
			inputMap.put(k_PlayPrevMeasure,"playprevmeasure");
			actionMap.put("playprevmeasure", rToA(this::playTToPreviousMeasure));
			inputMap.put(k_PlayNextMeasure,"playnextmeasure");
			actionMap.put("playnextmeasure", rToA(this::playTToNextMeasure));
			
			inputMap.put(k_Escape,"escape");
			actionMap.put("escape", rToA(this::cancelSelectionMode));
			inputMap.put(k_CtrlL,"ctrll");
			actionMap.put("ctrll", rToA(this::ctrlL));
			inputMap.put(k_CtrlC,"ctrlc");
			actionMap.put("ctrlc", rToA(this::ctrlC));
			inputMap.put(k_CtrlX,"ctrlx");
			actionMap.put("ctrlx", rToA(this::ctrlX));
			inputMap.put(k_CtrlV,"ctrlv");
			actionMap.put("ctrlv", rToA(this::ctrlV));
			inputMap.put(k_CtrlR,"ctrlr");
			actionMap.put("ctrlr", rToA(this::ctrlR));
			inputMap.put(k_CtrlS,"ctrls");
			actionMap.put("ctrls", rToA(this::ctrlS));
			inputMap.put(k_CtrlShiftS,"ctrlshifts");
			actionMap.put("ctrlshifts", rToA(this::showSaveAs));
			inputMap.put(k_CtrlI,"ctrli");
			actionMap.put("ctrli", rToA(this::ctrlI));
			
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
			
			inputMap.put(k_Comma,"comma");
			actionMap.put("comma", rToA(this::comma));
			
			inputMap.put(k_Space,"space");
			actionMap.put("space", rToA(TabbyCat.this::togglePlayStatus));
			
			inputMap.put(k_Backspace,"backspace");
			actionMap.put("backspace", rToA(this::backspace));
			
			inputMap.put(k_Hyphen,"hyphen");
			actionMap.put("hyphen", rToA(this::hyphen));

			inputMap.put(k_Delete,   "delete");
			actionMap.put("delete",  rToA(this::delete));
			inputMap.put(k_Home,     "home");
			actionMap.put("home",    rToA(this::home));
			inputMap.put(k_End,      "end");
			actionMap.put("end",     rToA(this::end));
			inputMap.put(k_PageUp,   "pageup");
			actionMap.put("pageup",  rToA(this::pageUp));
			inputMap.put(k_PageDown, "pagedown");
			actionMap.put("pagedown",rToA(this::pageDown));
			
			for (char c = 'A'; c <= 'Z'; c++) {
				char c_ = c;
				KeyStroke k = KeyStroke.getKeyStroke(""+c);
				inputMap.put(k,""+c);
				actionMap.put(""+c, rToA(()->handleCharInput(c_)));
			}
			for (char c = '0'; c <= '9'; c++) {
				char c_ = c;
				KeyStroke k = KeyStroke.getKeyStroke(""+c);
				inputMap.put(k,""+c);
				actionMap.put(""+c, rToA(()->handleCharInput(c_)));
			}

			this.addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					requestFocusInWindow();
					if (SwingUtilities.isRightMouseButton(e)) {
						ctrlV();
						return;
					}
					double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
					double scaledX = e.getX() * deviceScale / displayScale;
					double scaledY = e.getY() * deviceScale / displayScale;
					if (scaledY <= lastTopBarHeight + 5) {
						for (Map.Entry<SequencePosition, Rectangle2D> entry : menuItemBounds.entrySet()) {
							if (entry.getValue().contains(scaledX, scaledY)) {
								sequencePosition = entry.getKey();
								isInGrid = false;
								enter();
								return;
							}
						}
						return;
					}
					double mx = scaledX;
					double my = scaledY - lastVerticalTranslate;
					/*
					//System.out.println(mx+" "+my);
					//System.out.println(lastCanvasGrids);
					for (int i = 0; i < lastCanvasGrids.size(); i++) {
						Rectangle2D bounds = lastCanvasGrids.get(i).getBounds2D();
						if (bounds.contains(mx,my)) {
							System.out.println(bounds);
							//System.out.print(projectData.getCanvases().getCanvases().get(i-1));
							//lastCanvasGrids.get(i)	
						}
						
					}
					*/	
					
					for (int i = 0; i < lastCanvasGrids.size(); i++) {
						Rectangle2D bounds = lastCanvasGrids.get(i).getBounds2D();
						if (bounds.contains(mx, my)) {
							int clickedT = projectData.getViewT().get()
									+ (int) ((mx - bounds.getMinX()) / lastCellWidth);
							int clickedRelativeRow = (int) ((my - bounds.getMinY()) / lastRowHeight);

							int maxRelRow = (i == 0)
									? numEventRows
									: projectData.getCanvases().getCanvases().get(i - 1).getRowCount();
							if (clickedRelativeRow < 0 || clickedRelativeRow >= maxRelRow) break;

							int absoluteRow = (i == 0)
									? clickedRelativeRow
									: rowBreaks.get(i - 1) + 1 + clickedRelativeRow;

							if (e.isShiftDown()) {
								projectData.getCursorT().set(Math.max(0, clickedT));
								projectData.getSelectedRow().set(absoluteRow);
								isInGrid = true;
								if (!isSelectionMode.get()) {
									isMouseLasso = true;
									toggleSelectionMode();
								}
							} else if ((e.getModifiersEx() & java.awt.event.InputEvent.META_DOWN_MASK) != 0) {
								projectData.getPlaybackT().set(Math.max(0, clickedT));
							} else {
								projectData.getCursorT().set(Math.max(0, clickedT));
								projectData.getSelectedRow().set(absoluteRow);
								isInGrid = true;
							}
							repaint();
							break;
						}
					}

				}
				@Override
				public void mouseReleased(MouseEvent e) {
					if (!isMouseLasso) return;
					isMouseLasso = false;
					double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
					double scaledX = e.getX() * deviceScale / displayScale;
					double scaledY = e.getY() * deviceScale / displayScale;
					Pair<Integer,Integer> coords = gridCoordsFromMouse(scaledX, scaledY);
					if (coords != null) {
						projectData.getCursorT().set(Math.max(0, coords.a));
						projectData.getSelectedRow().set(coords.b);
					}
					if (e.isMetaDown()) {
						ctrlX();
					} else {
						ctrlC();
					}
				}
			});
			this.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent e) {
					double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
					double scaledX = e.getX() * deviceScale / displayScale;
					double scaledY = e.getY() * deviceScale / displayScale;
					Pair<Integer,Integer> coords = gridCoordsFromMouse(scaledX, scaledY);
					if (coords != null) {
						if (isMouseLasso) {
							projectData.getCursorT().set(Math.max(0, coords.a));
							projectData.getSelectedRow().set(coords.b);
							repaint();
						} else if (!isSelectionMode.get()) {
							projectData.getCursorT().set(Math.max(0, coords.a));
							projectData.getSelectedRow().set(coords.b);
							isInGrid = true;
							repaint();
						}
					}
				}
			});
			this.addMouseWheelListener(e -> {
				double delta = e.getPreciseWheelRotation();
				if (e.isShiftDown()) {
					horizScrollAccum += delta;
					int ticks = (int) horizScrollAccum;
					if (ticks != 0) {
						horizScrollAccum -= ticks;
						projectData.getViewT().set(Math.max(0, projectData.getViewT().get() + ticks));
						repaint();
					}
				} else {
					vertScrollAccum += delta;
					int px = (int) vertScrollAccum;
					if (px != 0) {
						vertScrollAccum -= px;
						viewY = Math.max(0, viewY + px);
						repaint();
					}
				}
			});
		}

		void hyphen() {
			if (isInGrid) {
				Pair<Integer,Integer> pair = 
						getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
				int canvasNum = pair.a;
				int relativeRow = pair.b;
				if (canvasNum == 0) {
					return;
				}
				CanvasConfig canvasConfig = projectData.getCanvases().getCanvases().get(canvasNum-1);
				if (canvasConfig instanceof DrumCanvasConfig) {
					return;
				}
				for (int t = projectData.getCursorT().get()+1; 
						projectData.getInstrumentData().get(new InstrumentDataKey(canvasConfig.getName(),t,relativeRow)) != null &&
						projectData.getInstrumentData().get(new InstrumentDataKey(canvasConfig.getName(),t,relativeRow)).charAt(0) == '-' ;
						t++) {
					InstrumentDataKey dk = new InstrumentDataKey(canvasConfig.getName(),t,relativeRow);
					projectData.getInstrumentData().remove(dk);
				}
				for (int t = projectData.getCursorT().get();
						projectData.getInstrumentData().get(new InstrumentDataKey(canvasConfig.getName(),t,relativeRow)) == null;
						t--) {
					InstrumentDataKey dk = new InstrumentDataKey(canvasConfig.getName(),t,relativeRow);
					projectData.getInstrumentData().put(dk,"-");
				}
				repaint();
			}
			
		}
		
		void ctrlS() {
			if (activeFile.get() == null) {
				showSaveAs();
			} else {
				if (fileHasBeenModified.get()) {
					try {
						saveXML(activeFile.get());
						fileHasBeenModified.set(false);
						updateWindowTitle();
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			}
		}

		void showSaveAs() {
			saveProjectPanel.setFileName(
				activeFile.get() != null
					? activeFile.get().getName()
					: String.format("%s.meow",
						DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(LocalDateTime.now(ZoneId.of("Z")))));
			cardLayout.show(cardPanel, saveProjectCardKey);
		}

		void ctrlI() {
			if (projectData == null) return;
			Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNum = p.a;
			if (canvasNum < 1 || canvasNum > projectData.getCanvases().getCanvases().size()) return;
			CanvasConfig canvas = projectData.getCanvases().getCanvases().get(canvasNum - 1);
			if (!(canvas instanceof StringCanvasConfig)) return;
			instrumentSettingsPanel.prepare((StringCanvasConfig) canvas);
			cardLayout.show(cardPanel, instrumentSettingsCardKey);
		}

		void backspace() {
			if (!instrumentClipboard.isEmpty() || !eventClipboard.isEmpty()) {
				ctrlC();
				return;
			}
			if (!fileHasBeenModified.get()) {
				fileHasBeenModified.set(true);
				updateWindowTitle();
			}
			Pair<Integer,Integer> pair = 
					getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNumber = pair.a;
			int row = pair.b;
			if (canvasNumber == 0) {
				projectData.getEventData().remove(
						new Point(projectData.getCursorT().get(),row));
			} else {
				String name = projectData.getCanvases().getCanvases().get(canvasNumber-1).getName();
				InstrumentDataKey dataKey =
						new InstrumentDataKey(name,
								projectData.getCursorT().get(),row);
				projectData.getInstrumentData().remove(dataKey);
			}
			if (!fileHasBeenModified.get()) {
				fileHasBeenModified.set(true);
				updateWindowTitle();
			}
			updateMeasureLinePositions();
			repaint();
		}

		void delete() {
			if (!isInGrid) return;
			Pair<Integer,Integer> pair = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNumber = pair.a;
			int row = pair.b;
			if (canvasNumber == 0) {
				projectData.getEventData().remove(new Point(projectData.getCursorT().get(), row));
			} else {
				String name = projectData.getCanvases().getCanvases().get(canvasNumber-1).getName();
				projectData.getInstrumentData().remove(new InstrumentDataKey(name, projectData.getCursorT().get(), row));
			}
			if (!fileHasBeenModified.get()) {
				fileHasBeenModified.set(true);
				updateWindowTitle();
			}
			updateMeasureLinePositions();
			repaint();
		}

		void home() {
			if (!isInGrid) return;
			projectData.getCursorT().set(0);
			projectData.getViewT().set(0);
			repaint();
		}

		void end() {
			if (!isInGrid) return;
			advanceCursorToFinalEvent();
		}

		void pageUp() {
			if (!isInGrid) return;
			double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
			int page = (int)((getWidth() * deviceScale / displayScale / getCellWidth()) * 0.8);
			projectData.getCursorT().updateAndGet(i -> Math.max(0, i - page));
			projectData.getViewT().updateAndGet(i -> Math.max(0, i - page));
			repaint();
		}

		void pageDown() {
			if (!isInGrid) return;
			double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
			int page = (int)((getWidth() * deviceScale / displayScale / getCellWidth()) * 0.8);
			projectData.getCursorT().getAndUpdate(i -> i + page);
			projectData.getViewT().getAndUpdate(i -> i + page);
			repaint();
		}

		void handleCharInput(char c) {
			if (!fileHasBeenModified.get()) {				
				fileHasBeenModified.set(true);
				updateWindowTitle();				
			}
			
			Pair<Integer,Integer> pair = 
					getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNumber = pair.a;
			int row = pair.b;
			if (canvasNumber == 0) {
				//event canvas
				if (c == 'T') {
					cardLayout.show(cardPanel, timeSignatureEventCardKey);
				} else if (c == 'S') {
					cardLayout.show(cardPanel, tempoEventCardKey);
				} else if (c == 'F') {
					cardLayout.show(cardPanel, shuffleEventCardKey);
				} else if (c == 'N') {
					cardLayout.show(cardPanel, notesEventCardKey);
				}
			} else {
				CanvasConfig canvasConfig = 
						projectData.getCanvases().getCanvases().get(canvasNumber-1);
				InstrumentDataKey dataKey = new InstrumentDataKey(canvasConfig.getName(),projectData.getCursorT().get(),row);
				String token0 = projectData.getInstrumentData().getOrDefault(dataKey,"");
				String token1 = token0+c;
				if (canvasConfig.willAccept(token1,row)) {
					projectData.getInstrumentData().put(dataKey, token1);
					playPreviewNote(canvasConfig, token1, row);
					repaint();
				}
			}
			
		}
		
		void ctrlR() {
			if (isInGrid) {
				if (projectData.getRepeatT().get() == projectData.getCursorT().get()+1) {
					projectData.getRepeatT().set(-1);
				} else {
					projectData.getRepeatT().set(projectData.getCursorT().get()+1);
				}
				
				repaint();
			}
		}
		
		void ctrlL() {
			if (isInGrid) {
				toggleSelectionMode();
			}
		}
		
		void ctrlC() {
			
			instrumentClipboard.clear();
			eventClipboard.clear();
			
			if (isSelectionMode.get()) {
				Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
				
				int tMin = Math.min(projectData.getCursorT().get(),lassoT0);
				int tMax = Math.max(projectData.getCursorT().get(),lassoT0);
				int rowMin = Math.min(lassoRow0, p.b);
				int rowMax = Math.max(lassoRow0, p.b);
				if (lassoCanvasNumber == 0) {
					
					Map<Point,ControlEvent> copied = new HashMap<>();
					int minT = Integer.MAX_VALUE;
					int minR = Integer.MAX_VALUE;
					for (int t = tMin; t <= tMax; t++) {
						for (int r = rowMin; r <=rowMax; r++) {
							Point point = new Point(t,r);
							if (projectData.getEventData().containsKey(point)) {
								minT = t<minT?t:minT;
								minR = r<minR?r:minR;
								copied.put(point,projectData.getEventData().get(point));
							}
						}
					}
					Map<Point,ControlEvent> normalized= new HashMap<>();
					for (Point point : copied.keySet()) {
						normalized.put(new Point(point.x-minT,point.y-minR), 
								copied.get(point));
					}
					eventClipboard.putAll(normalized);
					
				} else {
					CanvasConfig canvas= projectData.getCanvases().getCanvases().get(p.a-1);
										
					Map<InstrumentDataKey,String> copied = new HashMap<>();
					int minT = Integer.MAX_VALUE;
					int minR = Integer.MAX_VALUE;
					for (int t = tMin; t <= tMax; t++) {
						for (int r = rowMin; r <=rowMax; r++) {
							InstrumentDataKey dk = new InstrumentDataKey(canvas.getName(),t,r);
							if (projectData.getInstrumentData().containsKey(dk)) {
								minT = t<minT?t:minT;
								minR = r<minR?r:minR;
								copied.put(dk,projectData.getInstrumentData().get(dk));
							}
						}
					}
					Map<InstrumentDataKey,String> normalized= new HashMap<>();
					for (InstrumentDataKey dk : copied.keySet()) {
						normalized.put(new InstrumentDataKey(dk.getInstrumentName(),dk.getTime()-minT,dk.getRow()-minR), 
								copied.get(dk));												
					}
					instrumentClipboard.putAll(normalized);
				}
			}
			
			isSelectionMode.set(false);
			repaint();
		}
		
		void ctrlX() {
			ctrlC();
			
			Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			
			int tMin = Math.min(projectData.getCursorT().get(),lassoT0);
			int tMax = Math.max(projectData.getCursorT().get(),lassoT0);
			int rowMin = Math.min(lassoRow0, p.b);
			int rowMax = Math.max(lassoRow0, p.b);
			for (int t = tMin; t <= tMax; t++) {
				for (int r = rowMin; r <=rowMax; r++) {
					if (lassoCanvasNumber == 0) {
						projectData.getEventData().remove(new Point(t,r));
					} else {
						CanvasConfig canvas= projectData.getCanvases().getCanvases().get(p.a-1);
						InstrumentDataKey dk = new InstrumentDataKey(canvas.getName(),t,r);
						
						projectData.getInstrumentData().remove(dk);									
					}
				}
			}


			isSelectionMode.set(false);
			updateMeasureLinePositions();
			repaint();
		}

		void ctrlV() {
			Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			if (!isSelectionMode.get() && lassoCanvasNumber >= 0) {
				if (p.a == 0) {
					//then draw events if there are any
					if (!eventClipboard.isEmpty()) {
						
					}
				} else {
					if (!instrumentClipboard.isEmpty()) {
						CanvasConfig canvasConfig = projectData.getCanvases().getCanvases().get(p.a-1);
						CanvasConfig lassoCanvasConfig = projectData.getCanvases().getCanvases().get(lassoCanvasNumber-1);
						if (lassoCanvasConfig.getType() == canvasConfig.getType()) {
							int yOffset = instrumentClipboard.keySet().stream().mapToInt(a->a.getRow()).max().getAsInt();

							for (Entry<InstrumentDataKey, String> entry : instrumentClipboard.entrySet()) {

								int t = entry.getKey().getTime()+projectData.getCursorT().get();
								int r = entry.getKey().getRow()+p.b-yOffset;
								
								InstrumentDataKey dk = new InstrumentDataKey(entry.getKey().getInstrumentName(),t,r);
								System.out.println(dk+" "+entry.getValue());
								if (canvasConfig.willAccept(entry.getValue(), r)) {
									projectData.getInstrumentData().put(dk, entry.getValue());
								}

							};								
						}

					}
				}
			}
			repaint();
		}
		
		private Pair<Integer,Integer> gridCoordsFromMouse(double scaledX, double scaledY) {
			if (scaledY <= lastTopBarHeight + 5) return null;
			double mx = scaledX;
			double my = scaledY - lastVerticalTranslate;
			for (int i = 0; i < lastCanvasGrids.size(); i++) {
				Rectangle2D bounds = lastCanvasGrids.get(i).getBounds2D();
				if (bounds.contains(mx, my)) {
					int clickedT = projectData.getViewT().get()
							+ (int) ((mx - bounds.getMinX()) / lastCellWidth);
					int clickedRelativeRow = (int) ((my - bounds.getMinY()) / lastRowHeight);
					int maxRelRow = (i == 0)
							? numEventRows
							: projectData.getCanvases().getCanvases().get(i - 1).getRowCount();
					if (clickedRelativeRow < 0 || clickedRelativeRow >= maxRelRow) return null;
					int absoluteRow = (i == 0)
							? clickedRelativeRow
							: rowBreaks.get(i - 1) + 1 + clickedRelativeRow;
					return new Pair<>(clickedT, absoluteRow);
				}
			}
			return null;
		}

		void cancelSelectionMode() {
			if (isSelectionMode.get()) {
				isSelectionMode.set(false);
				lassoCanvasNumber = -1;
				lassoT0 = -1;
				lassoRow0 = -1;
				isMouseLasso = false;
				repaint();
			} else if (!instrumentClipboard.isEmpty() || !eventClipboard.isEmpty()) {
				instrumentClipboard.clear();
				eventClipboard.clear();
				lassoCanvasNumber = -1;
				repaint();
			}
		}

		public void toggleSelectionMode() {
			
			isSelectionMode.set(!isSelectionMode.get());
			if (isSelectionMode.get()) {
				Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
				this.lassoCanvasNumber = p.a;
				this.lassoT0 = projectData.getCursorT().get();
				this.lassoRow0 = p.b;
				//System.out.println("row0 is "+lassoRow0);

				
			} else {
				this.lassoCanvasNumber = -1;
				this.lassoT0 = -1;
				this.lassoRow0 = -1;
								
			}
			repaint();
		
		}
		void ctrlLeft() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.CTRL_LEFT);
			}
		}
		
		void ctrlRight() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.CTRL_RIGHT);
			}
		}

		void shiftUp() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.SHIFT_UP);
			}
		}
		
		void shiftDown() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.SHIFT_DOWN);
			}
		}
		
		void shiftLeft() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.SHIFT_LEFT);
			}
		}
		
		void shiftRight() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.SHIFT_RIGHT);
			}
		}
		
		void ctrlShiftUp() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.CTRL_SHIFT_UP);
			}
		}
		
		void ctrlShiftDown() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.CTRL_SHIFT_DOWN);
			}
		}
		
		void ctrlShiftLeft() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.CTRL_SHIFT_LEFT);
			}
		}
		
		void ctrlShiftRight() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.CTRL_SHIFT_RIGHT);
			}
		}
		
		double getCellWidth() {
			return gridFontMetrics.stringWidth("88.");
		}

		int computeNeededHeight() {
			FontMetrics topMetrics = new Canvas().getFontMetrics(new Font("SansSerif", Font.PLAIN, 14));
			FontMetrics gridMetrics = new Canvas().getFontMetrics(new Font("Monospaced", Font.BOLD, 12));
			int topBarHeight = topMetrics.getMaxAscent();
			int rowHeight = gridMetrics.getMaxAscent() + 4;
			int unscaled = topBarHeight * 3 + 5
					+ rowHeight * (2 + numEventRows);
			for (CanvasConfig canvas : projectData.getCanvases().getCanvases()) {
				unscaled += rowHeight * (3 + canvas.getRowCount());
			}
			return (int)(unscaled * displayScale) + frame.getInsets().top + frame.getInsets().bottom;
		}
		
		public final int getMaxVisibleTime() {
			int t0 = projectData.getViewT().get();
			
			double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
			double tDelta = (getWidth() * deviceScale / displayScale / getCellWidth());
			double t1 = t0 + tDelta;
			return (int) t1;
		}
		
		public void cursorTToPrevMeasure() {
			NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(projectData.getCursorT().get(), false);
			if (!headMap.isEmpty()) {
				Entry<Integer, Integer> last = headMap.lastEntry();
				projectData.getCursorT().set(last.getKey());
			}
			while (projectData.getCursorT().get() < projectData.getViewT().get() + scrollTimeMargin
					&& projectData.getViewT().get() > 0) {
				projectData.getViewT().getAndDecrement();
			}

		}
		
		public void cursorTToNextMeasure() {

			NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(projectData.getCursorT().get(), false);
			if (!tailMap.isEmpty()) {
				Entry<Integer, Integer> next = tailMap.firstEntry();
				projectData.getCursorT().set(next.getKey());
			}
			while (projectData.getCursorT().get() > getMaxVisibleTime() + scrollTimeMargin) {
				projectData.getViewT().getAndIncrement();
			}
		

		}
		public void playTToPreviousMeasure() {
			NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(projectData.getPlaybackT().get(),
					false);
			if (!headMap.isEmpty()) {
				Entry<Integer, Integer> last = headMap.lastEntry();
				projectData.getPlaybackT().set(last.getKey());
			}
			while (projectData.getPlaybackT().get() < projectData.getViewT().get() + scrollTimeMargin
					&& projectData.getViewT().get() > 0) {
				projectData.getViewT().getAndDecrement();
			}
			repaint();
		}

		public void playTToNextMeasure() {
			NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(projectData.getPlaybackT().get(),
					false);
			if (!tailMap.isEmpty()) {
				Entry<Integer, Integer> next = tailMap.firstEntry();
				projectData.getPlaybackT().set(next.getKey());
			}
			while (projectData.getPlaybackT().get() > getMaxVisibleTime() - scrollTimeMargin) {
				projectData.getViewT().getAndIncrement();
			}
			repaint();
		}
		
		int getMaxRow() {
			int maxRow = numEventRows + projectData.getCanvases().getCanvases().stream().mapToInt(a->a.getRowCount()).sum();
			return maxRow;
		}
		void handleGridMovement(CardinalDirection dir) {
			int maxRow = getMaxRow();
			Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNum = p.a;
			int relativeRow = p.b;
			int maxSelectedRow = canvasNum == 0 ? 2 : projectData.getCanvases().getCanvases().get(canvasNum-1).getRowCount();
			switch (dir) {
			case DOWN:
				if (isSelectionMode.get()) {
					if (relativeRow < maxSelectedRow-1) {
						projectData.getSelectedRow().incrementAndGet();
					}
				} else {
									
					projectData.getSelectedRow().updateAndGet(i->(i+1)%maxRow);
				}
				repaint();
				break;
			case LEFT:
				projectData.getCursorT().updateAndGet(i->Math.max(0, i-1));
				if (projectData.getCursorT().get() < projectData.getViewT().get() + scrollTimeMargin
						&& projectData.getViewT().get() > 0) {
					projectData.getViewT().getAndDecrement();
				}
				repaint();
				break;
			case RIGHT:
				projectData.getCursorT().incrementAndGet();
				
				if (projectData.getCursorT().get() >= getMaxVisibleTime()) {
					projectData.getViewT().getAndIncrement();
				}
				repaint();
				break;
			case UP:
				if (isSelectionMode.get()) {
					if (relativeRow > 0) {
						projectData.getSelectedRow().getAndDecrement();
					}
				} else {
					if (projectData.getSelectedRow().get() == 0) {
						projectData.getSelectedRow().set(maxRow-1);					
					} else {
						projectData.getSelectedRow().getAndDecrement();
					}
				}
				repaint();
				break;
			case SHIFT_UP:
				projectData.getSelectedRow().set(0);
				repaint();
				break;
			case SHIFT_DOWN:
				projectData.getSelectedRow().set(maxRow-1);
				repaint();
				break;
			case SHIFT_LEFT:
				cursorTToPrevMeasure();
				repaint();
				break;
			case SHIFT_RIGHT:
				cursorTToNextMeasure();
				repaint();
				break;
			case CTRL_SHIFT_UP:
				projectData.getSelectedRow().set(0);
				repaint();
				break;
			case CTRL_SHIFT_DOWN:
				projectData.getSelectedRow().set(maxRow-1);
				repaint();
				break;
			case CTRL_SHIFT_LEFT:
				projectData.getCursorT().set(0);
				projectData.getViewT().set(0);
				repaint();
				break;
			case CTRL_SHIFT_RIGHT:
				this.advanceCursorToFinalEvent();
				repaint();				
				break;
			case CTRL_LEFT:
				decrementPlayT();
				repaint();
				break;
			case CTRL_RIGHT:
				incrementPlayT();
				repaint();
				break;
			}
		}
		
		void comma() {
			
			isInGrid = !isInGrid;
			repaint();
		}
		
		Queue<Instant> tapTimes = new ArrayBlockingQueue<>(10);
		void handleTapperTap() {
			Instant now = Instant.now();
			if (!tapTimes.offer(now)) {
				tapTimes.poll();
				tapTimes.offer(now);
			}
			if (tapTimes.size() >= 3) {
				List<Instant> taps = new ArrayList<>(tapTimes);
				List<Duration> durations = 
						IntStream.range(1,tapTimes.size()).mapToObj(i -> 
						Duration.between(taps.get(i-1),taps.get(i)))
						.collect(java.util.stream.Collectors.toList());
				double secs = durations.stream().mapToLong(a->a.toNanos()).average().getAsDouble()
						/1000000000d;
						
				projectData.getTempo().set((int) (60.0/secs));
				if (projectData.getCursorT().get() == 0) {
					projectData.getInitialTempo().set(projectData.getTempo().get());
				}
				//projectData.getTempo().set(110);//(int) bpm);
				
			}
		}
		void enter() {
			if (isInGrid) {
				if (!instrumentClipboard.isEmpty() || !eventClipboard.isEmpty()) {
					ctrlV();
				}
				return;
			}
			switch (sequencePosition) {
			case NEW:
				cardLayout.show(cardPanel, newProjectCardKey);
				break;
			case OPEN:
				cardLayout.show(cardPanel, loadProjectCardKey);
				break;
			case SAVE:
				mainInterfacePanel.ctrlS();
				break;
			case SAVE_AS:
				mainInterfacePanel.showSaveAs();
				break;
			case SETTINGS:
				cardLayout.show(cardPanel, settingsCardKey);
				break;
			case HELP:
				cardLayout.show(cardPanel, helpCardKey);
				break;
			case TAPPER:
				handleTapperTap();
				break;
			case TEMPO:
				break;
			default:
				break;
			}
			repaint();
		}
				 
		void up() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.UP);
			} else {
				if (sequencePosition == SequencePosition.TEMPO) {
					projectData.getTempo().incrementAndGet();
					if (projectData.getCursorT().get() == 0) {
						projectData.getInitialTempo().set(projectData.getTempo().get());
					}
				} else if (sequencePosition == SequencePosition.SHUFFLE) {
					projectData.getShuffle().updateAndGet(i -> Math.min(90, i + 1));
				}
			}
			repaint();
		}

		void down() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.DOWN);
			} else {
				if (sequencePosition == SequencePosition.TEMPO) {
					projectData.getTempo().decrementAndGet();
					if (projectData.getCursorT().get() == 0) {
						projectData.getInitialTempo().set(projectData.getTempo().get());
					}
				} else if (sequencePosition == SequencePosition.SHUFFLE) {
					projectData.getShuffle().updateAndGet(i -> Math.max(-90, i - 1));
				}
			}
			repaint();
		}
		
		void left() {
			
			if (isInGrid) {
				handleGridMovement(CardinalDirection.LEFT);
			} else {
					
				int index =Arrays.asList(SequencePosition.values()).indexOf(sequencePosition);
				if (index == 0) {
					return;
				}
				sequencePosition = SequencePosition.values()[index-1];
			}
			repaint();
		}
		
		void right() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.RIGHT);
			} else {
				int index =Arrays.asList(SequencePosition.values()).indexOf(sequencePosition);
				if (index == SequencePosition.values().length-1) {
					return;
				}
				sequencePosition = SequencePosition.values()[index+1];
			}
			repaint();
		}
		
		public void advanceCursorToFinalEvent() {
			int lastT = 0;
			for (Point p : projectData.getEventData().keySet()) {
				lastT = Math.max(lastT, p.x);
			}
			for (InstrumentDataKey k : projectData.getInstrumentData().keySet()) {
				lastT = Math.max(lastT, k.getTime());
			}
			projectData.getCursorT().set(lastT);
			double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
			int visibleCols = (int)(getWidth() * deviceScale / displayScale / getCellWidth());
			projectData.getViewT().set(Math.max(0, lastT - visibleCols + scrollTimeMargin));
			repaint();
		}
		

		public void incrementPlayT() {
			projectData.getPlaybackT().incrementAndGet();
			double deviceScale_ = getGraphicsConfiguration().getDefaultTransform().getScaleX();
			int visibleCols = (int)(getWidth() * deviceScale_ / displayScale / getCellWidth());
			int cursorCol = visibleCols / 4;
			int desiredViewT = projectData.getPlaybackT().get() - cursorCol;
			projectData.getViewT().set(Math.max(0, desiredViewT));
		}

		public void decrementPlayT() {
			projectData.getPlaybackT().getAndUpdate(i -> Math.max(0, i - 1));
			while (projectData.getPlaybackT().get() < projectData.getViewT().get() + scrollTimeMargin
					&& projectData.getViewT().get() > 0) {
				projectData.getViewT().getAndDecrement();
			}
			
		}
		
		final List<Integer> rowBreaks = new ArrayList<>();
		void calculateRowBreaks() {
			rowBreaks.clear();
			int i = numEventRows-1;
			rowBreaks.add(i);
			for (CanvasConfig a : projectData.getCanvases().getCanvases()) {
				i+=a.getRowCount();					
				rowBreaks.add(i);
			}
		}
		
		Pair<Integer,Integer> getCanvasNumberAndRelativeRow(int row2) {
			int canvasGridNum = 0;
			int relativeRow = 0;
			for (int row = 0; row < row2; row++) {
				relativeRow++;
				if (rowBreaks.contains(row)) {
					canvasGridNum++;
					relativeRow = 0;
				}
			}
			return new Pair<>(canvasGridNum,relativeRow);
		}
		 
		@Override
		public void paint(Graphics g_) {			 
			calculateRowBreaks();
			Graphics2D g = (Graphics2D) g_;
			g.setTransform(new AffineTransform());
			g.scale(displayScale, displayScale);
			double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
			double W = getWidth() * deviceScale / displayScale;
			double H = getHeight() * deviceScale / displayScale;
			Font gridFont = new Font("Monospaced",Font.BOLD,12);
			FontMetrics gridMetrics = g.getFontMetrics(gridFont);
			Font topFont = new Font("SansSerif",Font.PLAIN,14);			
			FontMetrics topFontMetrics = g.getFontMetrics(topFont);			
			g.setFont(topFont);
			int topBarHeight = topFontMetrics.getMaxAscent();
			lastTopBarHeight = topBarHeight + topFontMetrics.getMaxDescent();
			menuItemBounds.clear();
			Iterator<Double> hueIterator = DoubleStream.iterate(0f, i->i+0.07).iterator();
			Runnable iterateHue = () -> {
				g.setPaint(Color.getHSBColor(hueIterator.next().floatValue(),0.5f,1f));
			};
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(Color.BLACK);
			g.fill(new Rectangle2D.Double(0, 0, W, H));
			g.setPaint(Color.WHITE);


			AffineTransform at = new AffineTransform();
			at.translate(0, topBarHeight);
			Rectangle2D newBounds = topFontMetrics.getStringBounds("NEW", g);
			newBounds = at.createTransformedShape(newBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.NEW, newBounds);
			at.translate(newBounds.getWidth()+5,0);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.NEW?Color.GRAY:Color.BLACK);
			g.fill(newBounds);
			iterateHue.run();
			g.drawString("NEW",(int) newBounds.getMinX(),(int) newBounds.getMaxY());
			iterateHue.run();
			Rectangle2D openBounds = topFontMetrics.getStringBounds("OPEN", g);
			openBounds = at.createTransformedShape(openBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.OPEN, openBounds);
			at.translate(openBounds.getWidth()+5,0);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.OPEN?Color.GRAY:Color.BLACK);
			g.fill(openBounds);
			iterateHue.run();
			g.drawString("OPEN",(int) openBounds.getMinX(),(int) openBounds.getMaxY());
			iterateHue.run();
			Rectangle2D saveBounds = topFontMetrics.getStringBounds("SAVE", g);
			saveBounds = at.createTransformedShape(saveBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.SAVE, saveBounds);
			at.translate(saveBounds.getWidth()+5,0);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.SAVE?Color.GRAY:Color.BLACK);
			g.fill(saveBounds);
			iterateHue.run();
			g.drawString("SAVE",(int) saveBounds.getMinX(),(int) saveBounds.getMaxY());
			iterateHue.run();
			Rectangle2D saveAsBounds = topFontMetrics.getStringBounds("SAVE AS", g);
			saveAsBounds = at.createTransformedShape(saveAsBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.SAVE_AS, saveAsBounds);
			at.translate(saveAsBounds.getWidth()+5,0);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.SAVE_AS?Color.GRAY:Color.BLACK);
			g.fill(saveAsBounds);
			iterateHue.run();
			g.drawString("SAVE AS",(int) saveAsBounds.getMinX(),(int) saveAsBounds.getMaxY());
			iterateHue.run();
			String tempoString = String.format("TEMPO %03d",projectData.getTempo().get());
			Rectangle2D tempoBounds = topFontMetrics.getStringBounds(tempoString, g);
			tempoBounds = at.createTransformedShape(tempoBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.TEMPO, tempoBounds);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.TEMPO?Color.GRAY:Color.BLACK);
			g.fill(tempoBounds);
			iterateHue.run();
			g.drawString(tempoString,(int) tempoBounds.getMinX(),(int) tempoBounds.getMaxY());
			at.translate(tempoBounds.getWidth()+5,0);
			String shuffleString = String.format("SHUFFLE %+d%%", projectData.getShuffle().get());
			Rectangle2D shuffleBounds = topFontMetrics.getStringBounds(shuffleString, g);
			shuffleBounds = at.createTransformedShape(shuffleBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.SHUFFLE, shuffleBounds);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.SHUFFLE?Color.GRAY:Color.BLACK);
			g.fill(shuffleBounds);
			iterateHue.run();
			g.drawString(shuffleString,(int) shuffleBounds.getMinX(),(int) shuffleBounds.getMaxY());
			at.translate(shuffleBounds.getWidth()+5,0);
			String tapString = "TAP!";
			Rectangle2D tapBounds = topFontMetrics.getStringBounds(tapString, g);
			tapBounds = at.createTransformedShape(tapBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.TAPPER, tapBounds);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.TAPPER?Color.GRAY:Color.BLACK);
			g.fill(tapBounds);
			iterateHue.run();
			g.drawString(tapString,(int) tapBounds.getMinX(),(int) tapBounds.getMaxY());
			at.translate(tapBounds.getWidth()+5,0);
			String settingsString = "SETTINGS";
			Rectangle2D settingsBounds = topFontMetrics.getStringBounds(settingsString, g);
			settingsBounds = at.createTransformedShape(settingsBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.SETTINGS, settingsBounds);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.SETTINGS?Color.GRAY:Color.BLACK);
			g.fill(settingsBounds);
			iterateHue.run();
			g.drawString(settingsString,(int) settingsBounds.getMinX(),(int) settingsBounds.getMaxY());
			at.translate(settingsBounds.getWidth()+5,0);
			String helpString = "HELP";
			Rectangle2D helpBounds = topFontMetrics.getStringBounds(helpString, g);
			helpBounds = at.createTransformedShape(helpBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.HELP, helpBounds);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.HELP?Color.GRAY:Color.BLACK);
			g.fill(helpBounds);
			iterateHue.run();
			g.drawString(helpString,(int) helpBounds.getMinX(),(int) helpBounds.getMaxY());
			
			at.setToIdentity();
			
			at.translate(0, topBarHeight*2+5);//this might need to be changed if we exceed the size of the window
			
			g.setPaint(Color.WHITE);
			g.setFont(gridFont);
			g.drawLine(0, topBarHeight+5, (int)W, topBarHeight+5);
			Area clip = new Area(new Rectangle2D.Double(0, 0, W, H));
			clip.subtract(new Area(new Rectangle2D.Double(0, 0, W, topBarHeight+5)));
			g.setClip(clip);
			List<Shape> canvasGrids= new ArrayList<>();
			Map<String,Point2D> stringPositions = new HashMap<>();
			List<Rectangle2D> measurePanels = new ArrayList<>();
			Path2D.Double eventP2D = new Path2D.Double();
			int cellWidth = (int) getCellWidth();
			int rowHeight = gridMetrics.getMaxAscent()+4;
			int t0 = projectData.getViewT().get();
			int tDelta = (int) (W/cellWidth);
			int t1 = t0+tDelta;
			int x = 0;
			for (int t = t0; t <= t1; t++) {
				eventP2D.append(new Line2D.Double(
						new Point2D.Double(x,0),
						new Point2D.Double(x,rowHeight*numEventRows)),false);
				x+=cellWidth;
			}
			for (int r = -1; r < numEventRows; r++) {
				double y = (r+1)*rowHeight;
				eventP2D.append(new Line2D.Double(
						new Point2D.Double(0d,y),
						new Point2D.Double(W,y)),false);
			}
			
			String eventsString = "Events";
			Rectangle2D eventsStringBounds = topFontMetrics.getStringBounds(eventsString, g);
			eventsStringBounds = at.createTransformedShape(eventsStringBounds).getBounds2D();

			stringPositions.put(eventsString,new Point2D.Double(eventsStringBounds.getMinX(),eventsStringBounds.getMaxY())); 			

			at.translate(0,rowHeight);


			canvasGrids.add(at.createTransformedShape(eventP2D));			
			at.translate(0,eventP2D.getBounds2D().getHeight());
			
			Rectangle2D eventMeasuresPanel = new Rectangle2D.Double(0, 0, W, rowHeight);
			measurePanels.add(at.createTransformedShape(eventMeasuresPanel).getBounds2D());
			at.translate(0,eventMeasuresPanel.getHeight());
			
			for (CanvasConfig canvasConfig : projectData.getCanvases().getCanvases()) {
				String metadataString = String.format("%s",canvasConfig.getName());
				Rectangle2D metadataStringBounds = gridMetrics.getStringBounds(metadataString, g);
				at.translate(0,rowHeight);
				metadataStringBounds = at.createTransformedShape(metadataStringBounds).getBounds2D();
			
				stringPositions.put(metadataString, new Point2D.Double(metadataStringBounds.getMinX(),metadataStringBounds.getMaxY()));
			
				at.translate(0,rowHeight);
			
				Path2D.Double p2d = new Path2D.Double();
				x = 0;
				
				for (int t = t0; t <= t1; t++) {
					p2d.append(new Line2D.Double(
							new Point2D.Double(x,0),
							new Point2D.Double(x,rowHeight*canvasConfig.getRowCount())),false);
					x+=cellWidth;
				}
				for (int r = -1; r < canvasConfig.getRowCount(); r++) {
					double y = (r+1)*rowHeight;
					p2d.append(new Line2D.Double(
							new Point2D.Double(0d,y),
							new Point2D.Double(W,y)),false);
				}

				canvasGrids.add(at.createTransformedShape(p2d));
				at.translate(0,p2d.getBounds2D().getHeight());
				Rectangle2D measuresPanel = new Rectangle2D.Double(0, 0, W, rowHeight);
				measurePanels.add(at.createTransformedShape(measuresPanel).getBounds2D());
				at.translate(0,measuresPanel.getHeight());
				
			}

			lastCanvasGrids = new ArrayList<>(canvasGrids);
			lastCellWidth = cellWidth;
			lastRowHeight = rowHeight;

			Rectangle2D selectedGridBounds = new Rectangle2D.Double(0,0,1,1);
			Rectangle2D selectionRectangle = new Rectangle2D.Double(0,0,1,1);
			Pair<Integer,Integer> pair = 
					getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasGridNum = pair.a;
			int relativeRow = pair.b;
			{
				
				selectedGridBounds = canvasGrids.get(canvasGridNum).getBounds2D();
				
				double selectedX = selectedGridBounds.getMinX() + 
						(projectData.getCursorT().get()-projectData.getViewT().get())*cellWidth;
				double selectedY = selectedGridBounds.getMinY()+rowHeight*relativeRow;
				
				selectionRectangle = new Rectangle2D.Double(selectedX,selectedY,cellWidth,rowHeight);
				
			}
						
			lastVerticalTranslate = (int) Math.min(0, H-selectionRectangle.getMaxY()-rowHeight*2) - viewY;
			g.translate(0, lastVerticalTranslate);
			stringPositions.entrySet().forEach(entry -> {
				g.drawString(entry.getKey(),(int) entry.getValue().getX(),(int) entry.getValue().getY());
			});
			
			for (Shape canvasGrid : canvasGrids) {
				Rectangle2D bounds = canvasGrid.getBounds2D();
				int playbackX = (projectData.getPlaybackT().get()-projectData.getViewT().get())*cellWidth;
				g.setPaint(new Color(110,110,50));
				g.fill(new Rectangle2D.Double(playbackX, bounds.getMinY(), cellWidth, bounds.getHeight()));

			}
			if (isSelectionMode.get() && lassoCanvasNumber == canvasGridNum) {
				
				Set<Point> outerSelectionCells = new HashSet<>();
				Set<Point> innerSelectionCells = new HashSet<>();
				
				int sT0 = Math.min(projectData.getCursorT().get(), lassoT0);
				int sT1 = Math.max(projectData.getCursorT().get(), lassoT0);
				int row0 = Math.min(relativeRow,lassoRow0);
				int row1 = Math.max(relativeRow,lassoRow0);
				
				IntStream.rangeClosed(row0, row1).forEach(row -> {
					outerSelectionCells.add(new Point(sT0,row));
					outerSelectionCells.add(new Point(sT1,row));
				});
				
				IntStream.rangeClosed(sT0, sT1).forEach(t -> {
					outerSelectionCells.add(new Point(t,row0));
					outerSelectionCells.add(new Point(t,row1));
				});
				
				for (int row = row0+1; row<row1; row++) {
					for (int sT = sT0+1; sT<sT1; sT++) {
						innerSelectionCells.add(new Point(sT,row));
					}
				}
				Rectangle2D bounds = canvasGrids.get(lassoCanvasNumber).getBounds2D();
				g.setPaint(Color.GREEN);
				for (Point p : outerSelectionCells) {
					g.fill(new Rectangle2D.Double(
							bounds.getMinX()+(p.x-projectData.getViewT().get())*cellWidth,
							bounds.getMinY()+(p.y)*rowHeight,
							cellWidth,rowHeight));
							
				}
				g.setPaint(Color.GREEN.darker());
				for (Point p : innerSelectionCells) {
					g.fill(new Rectangle2D.Double(
							bounds.getMinX()+(p.x-projectData.getViewT().get())*cellWidth,
							bounds.getMinY()+(p.y)*rowHeight,
							cellWidth,rowHeight));
							
				}
				
			}
			
			g.setPaint(Color.WHITE);
			canvasGrids.forEach(g::draw);
			g.setPaint(Color.RED);
			g.draw(selectedGridBounds);
			g.setPaint(Color.RED);
			g.draw(selectionRectangle);
			int canvasNumber = 0;
			g.setFont(gridFont);
			for (Shape canvasGrid : canvasGrids) {				
				
				Rectangle2D bounds = canvasGrid.getBounds2D();
				int playbackStartX = (projectData.getPlaybackStartT().get()-projectData.getViewT().get())*cellWidth;
			
				int repeatX = (projectData.getRepeatT().get()-projectData.getViewT().get())*cellWidth;
				
				g.setPaint(Color.orange.darker());
				g.setStroke(new BasicStroke(1));
				if (projectData.getRepeatT().get()>=0) {
					g.draw(new Line2D.Double(repeatX, bounds.getMinY(), repeatX,bounds.getMaxY()));
					g.draw(new Line2D.Double(repeatX-3, bounds.getMinY(), repeatX-3,bounds.getMaxY()));
				}
				
				g.draw(new Line2D.Double(playbackStartX, bounds.getMinY(), playbackStartX,bounds.getMaxY()));
				g.draw(new Line2D.Double(playbackStartX+3, bounds.getMinY(), playbackStartX+3,bounds.getMaxY()));
				
				x = 0;
				g.setPaint(Color.white);
								
				String canvasName = canvasNumber == 0 ? "Events" :
					projectData.getCanvases().getCanvases().get(canvasNumber-1).getName();
				int rowCount = canvasNumber == 0 ? numEventRows : projectData.getCanvases().getCanvases().get(canvasNumber-1).getRowCount();
				for (int t = projectData.getViewT().get(); t<t1; t++) {
					
					for (int row = 0; row < rowCount; row++) {
						if (canvasNumber == 0) {
							ControlEvent event = projectData.getEventData().get(new Point(t,row));
							if (event != null) {
								switch (event.getType()) {
								
								case TIME_SIGNATURE: {
									g.setFont(gridFont.deriveFont(Font.ITALIC));
									g.setPaint(Color.YELLOW);
									String text = event.toString();
									g.drawString(text,
											x+(rowHeight-gridMetrics.stringWidth(text))/2,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									
									break;
								}
								case PROGRAM_CHANGE: {
									g.setFont(gridFont.deriveFont(Font.ITALIC));
									g.setPaint(new Color(100,200,255));
									String text = event.toString();
									g.drawString(text,
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
											
									g.setFont(gridFont);
									break;
								}								
								case TEMPO: {
									g.setFont(gridFont.deriveFont(Font.ITALIC));
									g.setPaint(new Color(255,200,100));
									String text = event.toString();
									g.drawString(text,
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									g.setFont(gridFont);
									break;
								}
								case SHUFFLE: {
									g.setFont(gridFont.deriveFont(Font.ITALIC));
									g.setPaint(new Color(100, 220, 255));
									g.drawString(event.toString(),
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									g.setFont(gridFont);
									break;
								}								
								case STICKY_NOTE: {
									g.setFont(gridFont.deriveFont(Font.ITALIC));
									g.setPaint(new Color(255, 220, 80));
									String text = ((StickyNote) event).getText();
									g.drawString(text,
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
								}
								default:
									break;					
								}					
							}					
						} else {
							InstrumentDataKey dataKey = new InstrumentDataKey(canvasName,t,row);
							if (projectData.getInstrumentData().containsKey(dataKey)) {
								String val = projectData.getInstrumentData().get(dataKey);
								g.drawString(val,x+(rowHeight-gridMetrics.stringWidth(val))/2,
										(int) (bounds.getMinY()+(row+1)*rowHeight-2));
							}
						}
					}					
						
					g.setStroke(new BasicStroke(2));
					g.setPaint(Color.getHSBColor(0.85f, 0.25f, 1f));
					
					if (cachedMeasurePositions.containsKey(t)) {
						g.draw(new Line2D.Double(
								new Point2D.Double(x,bounds.getMinY()),
								new Point2D.Double(x,bounds.getMaxY())));
					}				
					x += cellWidth;
				}

			
			
				if (!isSelectionMode.get() && lassoCanvasNumber >= 0) {
					if (canvasNumber == 0) {
						//then draw events if there are any
						if (!eventClipboard.isEmpty()) {
							
						}
					} else {
						if (canvasNumber == canvasGridNum && !instrumentClipboard.isEmpty()) {
							CanvasConfig canvasConfig = projectData.getCanvases().getCanvases().get(canvasNumber-1);
							CanvasConfig lassoCanvasConfig = projectData.getCanvases().getCanvases().get(lassoCanvasNumber-1);
							if (lassoCanvasConfig.getType() == canvasConfig.getType()) {
								int yOffset = instrumentClipboard.keySet().stream().mapToInt(a->a.getRow()).max().getAsInt();
								g.setPaint(Color.RED);
								for (Entry<InstrumentDataKey, String> entry : instrumentClipboard.entrySet()) {
									int x0 = (projectData.getCursorT().get()-projectData.getViewT().get())*cellWidth+(int)bounds.getMinX();					
									int y0 = (relativeRow+1-yOffset)*rowHeight+(int)bounds.getMinY();
									int x_ = entry.getKey().getTime()*cellWidth+x0;
									int y_ = y0+entry.getKey().getRow()*rowHeight;
									if (y_ > bounds.getMinY()) {
										g.drawString(entry.getValue(),x_+(cellWidth-gridFontMetrics.stringWidth(entry.getValue()))/2,y_);
									}
								};								
							}

						}
					}
				}
				canvasNumber++;

			}		
			
			for (Rectangle2D measurePanel : measurePanels) {
				x = 0;
				g.setPaint(Color.white);
				for (int t = projectData.getViewT().get(); t<t1; t++) {
					if (cachedMeasurePositions.containsKey(t)) {
						int measure = cachedMeasurePositions.get(t);
						g.drawString("" + measure, x, (int) measurePanel.getMaxY());
					}
					if (cachedBeatMarkerPositions.contains(t)) {
						g.drawString(".", x, (int) measurePanel.getMaxY());
					}
					x += cellWidth;
				}
			}
		}
	}
	
	class SaveProjectPanel extends JPanel {
		private File workingDir = defaultProjectPath;
		private StringBuffer fileName = new StringBuffer();
		private int selectedIndex = 0;
		
		public SaveProjectPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape,"esc");
			actionMap.put("esc", rToA(()->{ if (projectData != null) cardLayout.show(cardPanel, mainInterfaceCardKey); }));
			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
			inputMap.put(k_Backspace,"backspace");
			actionMap.put("backspace", rToA(this::backspace));
			for (char c = 'A'; c <= 'Z'; c++) {
				String upper = String.valueOf(c);				
				char lower = upper.toLowerCase().charAt(0);;
				KeyStroke withoutShiftKey= KeyStroke.getKeyStroke(upper);
				KeyStroke withShiftKey = KeyStroke.getKeyStroke("shift "+upper);
				inputMap.put(withoutShiftKey, lower+"");
				inputMap.put(withShiftKey, upper);
				char c_ = c;
				actionMap.put(lower+"", rToA(()->handleChar(lower)));
				actionMap.put(upper, rToA(()->handleChar(c_)));				
				
			}
			for (char c = '0'; c <= '9'; c++) {
				KeyStroke key = KeyStroke.getKeyStroke(c);
				inputMap.put(key, String.valueOf(c));
				char c_ = c;
				actionMap.put(String.valueOf(c),rToA(()->handleChar(c_)));
			}	
		}
		
		void backspace() {
			if (selectedIndex == 0 && fileName.length() > 0) {
				fileName.deleteCharAt(fileName.length()-1);
			}
		}
		
		public void setFileName(String f) {
			fileName.setLength(0);
			fileName.append(f);
			repaint();
		}
		
		public void up() {
			int numFiles = 0;
			for (File f : workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					numFiles++;
				}
			}
			
			if (selectedIndex==0) {
				selectedIndex = 1+numFiles;				
			} else {
				selectedIndex-=1;
			}
			repaint();
		}
		
		public void down() {
			int numFiles = 0;
			for (File f : workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					numFiles++;
				}
			}
			selectedIndex+=1;
			if (selectedIndex == 2+numFiles) {
				selectedIndex = 0;
			}
			repaint();
			
		}
		
		public void handleChar(char c) {
			if (selectedIndex == 0) {
				fileName.append(c);
			} 
			repaint();
		}
		
		public void enter() {
			if (selectedIndex == 0) {
				File f = new File(workingDir.getAbsolutePath()+"/"+fileName.toString());
				if (!f.getAbsolutePath().endsWith(".meow")) {
					f = new File(f.getAbsoluteFile() + ".meow");
				}
				try {
					saveXML(f);
				} catch (Exception ex) {
					if (exitAfterSave.getAndSet(false)) {
						javax.swing.JOptionPane.showMessageDialog(frame, ex.toString(), "Save Failed", javax.swing.JOptionPane.ERROR_MESSAGE);
						return;
					}
					ex.printStackTrace();
				}
				activeFile.set(f);
				fileHasBeenModified.set(false);
				updateWindowTitle();
				if (exitAfterSave.getAndSet(false)) {
					System.exit(0);
				}
				cardLayout.show(cardPanel, mainInterfaceCardKey);
				//cardLayout.show(cardPanel, newProjectCardKey);
			} else if (selectedIndex == 1) {
				this.workingDir = new File(workingDir.getAbsolutePath()).getParentFile();
				repaint();
			} else {
				List<File> files =
						Arrays.asList(workingDir.listFiles()).stream().filter(a->a.isDirectory() || fileFilter.accept(a))
						.collect(java.util.stream.Collectors.toList());
				if (files.get(selectedIndex-2).isDirectory()) {
					workingDir = new File(workingDir.getAbsolutePath()+"/"+files.get(selectedIndex-2).getName());
					selectedIndex= 1;
					repaint();
				} else {
					File f = new File(workingDir.getAbsolutePath()+"/"+fileName.toString());
					try {
						saveXML(f);
					} catch (Exception ex) {
						if (exitAfterSave.getAndSet(false)) {
							javax.swing.JOptionPane.showMessageDialog(frame, ex.toString(), "Save Failed", javax.swing.JOptionPane.ERROR_MESSAGE);
							return;
						}
						ex.printStackTrace();
					}
					activeFile.set(f);
					fileHasBeenModified.set(false);
					updateWindowTitle();
					if (exitAfterSave.getAndSet(false)) {
						System.exit(0);
					}
					cardLayout.show(cardPanel, mainInterfaceCardKey);

				}

			}
			
		}
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
						
			int y = textFontMetrics.getMaxAscent();
			g.setPaint(Color.RED);
			g.drawString(workingDir.getAbsolutePath(),
					getWidth()-textFontMetrics.stringWidth(workingDir.getAbsolutePath())-2, y);

			List<Pair<String,Color>> strings= new ArrayList<>();
			strings.add(new Pair<>(fileName.toString(),new Color(180,180,255)));
			strings.add(new Pair<>("..",new Color(255,255,180)));
			if (workingDir == null) {
				return;
			}
			for (File f : this.workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					strings.add(new Pair<>(f.getName(),f.isDirectory()?new Color(255,255,180):new Color(180,255,180)));
				}
			
			}
			int w = strings.stream().mapToInt(a->textFontMetrics.stringWidth(a.a)).max().getAsInt();
			for (int i = 0; i < strings.size(); i++) {
				Pair<String,Color> p = strings.get(i);
				g.setPaint(selectedIndex == i?Color.DARK_GRAY:Color.black);
				g.fillRect(0, y-textFontMetrics.getMaxAscent(), w, textFontMetrics.getMaxAscent());
				g.setPaint(p.b);
				g.drawString(p.a, 2, y);
				y+=textFontMetrics.getMaxAscent();
			}
		}
	}
	
	class TimeSignatureEventPanel extends JPanel {
		
		boolean flag = false;
		int numerator = 4;
		int denominator = 4;
		public TimeSignatureEventPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape,"esc");
			actionMap.put("esc", rToA(()->{ if (projectData != null) cardLayout.show(cardPanel, mainInterfaceCardKey); }));
			inputMap.put(k_Left,"left");
			actionMap.put("left", rToA(this::left));
			inputMap.put(k_Right,"right");
			actionMap.put("right", rToA(this::right));
			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
		}
		
		void up() {
			if (!flag) {
				numerator = Math.min(999, numerator+1);				
			} else {
				denominator = Math.min(16, denominator*2);
			}
			repaint();
		}
		
		void down() {
			if (!flag) {
				numerator = Math.max(1, numerator-1);				
			} else {
				denominator = Math.max(2, denominator/2);
			}
			repaint();
		}
		
		void enter() {
			
			TimeSignatureDenominator tsd = 
					TimeSignatureDenominator.fromInt(denominator).get();
			TimeSignatureEvent tse = 
					new TimeSignatureEvent(numerator,tsd);
			projectData.getEventData().put(
					new Point(projectData.getCursorT().get(),
							projectData.getSelectedRow().get()),
					tse);
			updateMeasureLinePositions();
			mainInterfacePanel.repaint();
			cardLayout.show(cardPanel, mainInterfaceCardKey);
			
		}
		
		void left() {
			flag=!flag;
			repaint();
		}
		void right() {
			flag=!flag;
			repaint();
		}
		
		@Override
		public void paint(Graphics g_) {
			
			Graphics2D g = (Graphics2D) g_;
			g.setFont(textFont);
			g.setPaint(Color.black);
			g.fill(getBounds());
			
			String numerLabel = "Numerator:";
			String denomLabel = "Denominator:";
			
			Rectangle2D numerLabelBounds = 
					textFontMetrics.getStringBounds(numerLabel, g);
			Rectangle2D numerBounds = 
					textFontMetrics.getStringBounds("000", g);
			Rectangle2D denomLabelBounds = 
					textFontMetrics.getStringBounds(denomLabel, g);
			Rectangle2D denomBounds = 
					textFontMetrics.getStringBounds("16", g);
			
			g.translate(0, numerLabelBounds.getHeight());
			g.setPaint(Color.WHITE);
			g.drawString(numerLabel,(int) numerLabelBounds.getMinX(), (int) numerLabelBounds.getMaxY());
			g.translate(numerLabelBounds.getWidth()+10,0);
			g.setPaint(!flag?Color.GRAY:Color.DARK_GRAY);
			g.fill(numerBounds);
			g.setPaint(new Color(255,255,100));
			g.drawString(numerator+"",(int) numerBounds.getMinX(), 0);
			g.setPaint(Color.WHITE);
			g.translate(numerBounds.getWidth()+10,0);
			g.drawString(denomLabel,(int) denomLabelBounds.getMinX(), (int) denomLabelBounds.getMaxY());
			g.translate(denomLabelBounds.getWidth()+10,0);
			g.setPaint(flag?Color.GRAY:Color.DARK_GRAY);
			g.fill(denomBounds);
			g.setPaint(new Color(255,255,100));
			g.drawString(denominator+"",(int) denomBounds.getMinX(), 0);
		}
	}
	
	class TempoEventPanel extends JPanel {
		int tempo = 120;
		public TempoEventPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape,"esc");
			actionMap.put("esc", rToA(()->{ if (projectData != null) cardLayout.show(cardPanel, mainInterfaceCardKey); }));
			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
		}
		void enter() {
			TempoEvent tempoEvent = new TempoEvent(tempo);
			tempo = 120;
			projectData.getEventData().put(
					new Point(projectData.getCursorT().get(),projectData.getSelectedRow().get()),
					tempoEvent);									
			cardLayout.show(cardPanel, mainInterfaceCardKey);		
		}
		
		void up() {
			tempo = Math.min(1000, tempo+1);
			repaint();
		}		
		void down() {
			tempo = Math.max(20, tempo-1);
			repaint();
		}
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.black);
			g.fill(getBounds());
			g.setPaint(Color.white);
			g.setFont(textFont);
			g.drawString("TEMPO: "+tempo,2, textFontMetrics.getMaxAscent());
		}
	}
	
	class ShuffleEventPanel extends JPanel {
		int shuffle = 0;
		public ShuffleEventPanel() {
			InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap am = getActionMap();
			im.put(k_Escape, "esc");
			am.put("esc",    rToA(() -> { if (projectData != null) cardLayout.show(cardPanel, mainInterfaceCardKey); }));
			im.put(k_Up,    "up");    am.put("up",    rToA(this::up));
			im.put(k_Down,  "down");  am.put("down",  rToA(this::down));
			im.put(k_Enter, "enter"); am.put("enter", rToA(this::enter));
		}
		void enter() {
			ShuffleEvent event = new ShuffleEvent(shuffle);
			shuffle = 0;
			projectData.getEventData().put(
				new Point(projectData.getCursorT().get(), projectData.getSelectedRow().get()),
				event);
			cardLayout.show(cardPanel, mainInterfaceCardKey);
		}
		void up()   { shuffle = Math.min(90,  shuffle + 1); repaint(); }
		void down() { shuffle = Math.max(-90, shuffle - 1); repaint(); }
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.BLACK);
			g.fill(getBounds());
			g.setPaint(Color.WHITE);
			g.setFont(textFont);
			g.drawString("SHUFFLE: " + shuffle, 2, textFontMetrics.getMaxAscent());
		}
	}

	class SettingsPanel extends JPanel {
		double uiScaleValue = UI_SCALE;
		int settingsFocus = 0; // 0 = UI Scale, 1 = Output Device

		public SettingsPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape, "esc");
			actionMap.put("esc", rToA(() -> { cardLayout.show(cardPanel, mainInterfaceCardKey); }));
			inputMap.put(k_Up, "up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down, "down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Left, "left");
			actionMap.put("left", rToA(this::adjustLeft));
			inputMap.put(k_Right, "right");
			actionMap.put("right", rToA(this::adjustRight));
			inputMap.put(k_Enter, "enter");
			actionMap.put("enter", rToA(this::enter));
		}

		void up() {
			settingsFocus = 0;
			repaint();
		}

		void down() {
			settingsFocus = 1;
			repaint();
		}

		void adjustLeft() {
			if (settingsFocus == 0) {
				uiScaleValue = Math.max(0.1, Math.round((uiScaleValue - 0.1) * 10.0) / 10.0);
				displayScale = uiScaleValue;
				if (projectData != null) projectData.setUiScale(uiScaleValue);
				repaint();
			}
		}

		void adjustRight() {
			if (settingsFocus == 0) {
				uiScaleValue = Math.min(10.0, Math.round((uiScaleValue + 0.1) * 10.0) / 10.0);
				displayScale = uiScaleValue;
				if (projectData != null) projectData.setUiScale(uiScaleValue);
				repaint();
			}
		}

		void enter() {
			if (settingsFocus == 1) {
				audioOutputPanel.refresh();
				cardLayout.show(cardPanel, audioOutputCardKey);
			}
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.black);
			g.fill(getBounds());

			Font scaledFont = textFont.deriveFont((float)(textFont.getSize() * uiScaleValue / UI_SCALE));
			FontMetrics fm = getFontMetrics(scaledFont);
			g.setFont(scaledFont);

			int lineH = fm.getHeight();
			int x = 10;
			int y = lineH;

			// --- UI Scale row ---
			boolean uiFocused = settingsFocus == 0;
			String uiLabel = "UI Scale:";
			String uiVal = String.format("%.1f", uiScaleValue);
			int uiLabelW = fm.stringWidth(uiLabel);
			Rectangle2D valBounds = fm.getStringBounds("00.0", g);

			g.setPaint(uiFocused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString(uiLabel, x, y);
			int valX = x + uiLabelW + 10;
			g.setPaint(uiFocused ? Color.GRAY : new Color(50, 50, 50));
			g.fillRect(valX, y + (int) valBounds.getMinY(), (int) valBounds.getWidth(), (int) valBounds.getHeight());
			g.setPaint(uiFocused ? new Color(255, 255, 100) : Color.GRAY);
			g.drawString(uiVal, valX, y);

			// --- Output Device row ---
			y += lineH + 8;
			boolean audioFocused = settingsFocus == 1;
			String deviceLabel = "Output Device:";
			String deviceName = (selectedAudioMixerInfo == null) ? "Default (system)" : selectedAudioMixerInfo.getName();
			int deviceLabelW = fm.stringWidth(deviceLabel);

			g.setPaint(audioFocused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString(deviceLabel, x, y);
			g.setPaint(audioFocused ? new Color(255, 255, 100) : Color.GRAY);
			g.drawString(" " + deviceName, x + deviceLabelW, y);

			// --- Nav hint ---
			y += lineH * 2;
			g.setPaint(new Color(70, 70, 70));
			if (uiFocused) {
				g.drawString("UP/DOWN: navigate   LEFT/RIGHT: adjust   ESC: back", x, y);
			} else {
				g.drawString("UP/DOWN: navigate   ENTER: open   ESC: back", x, y);
			}
		}
	}

	class AudioOutputPanel extends JPanel {
		List<Mixer.Info> mixerInfos = new ArrayList<>();
		int audioDeviceCursor = 0; // 0 = Default, 1+ = index into mixerInfos

		public AudioOutputPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape, "esc");
			actionMap.put("esc", rToA(() -> { cardLayout.show(cardPanel, settingsCardKey); }));
			inputMap.put(k_Up, "up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down, "down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter, "enter");
			actionMap.put("enter", rToA(this::enter));
		}

		void refresh() {
			mixerInfos = getOutputMixerInfos();
			audioDeviceCursor = 0;
			if (selectedAudioMixerInfo != null) {
				for (int i = 0; i < mixerInfos.size(); i++) {
					if (mixerInfos.get(i).getName().equals(selectedAudioMixerInfo.getName())) {
						audioDeviceCursor = i + 1;
						break;
					}
				}
			}
		}

		void up() {
			int total = mixerInfos.size() + 1;
			audioDeviceCursor = (audioDeviceCursor - 1 + total) % total;
			repaint();
		}

		void down() {
			int total = mixerInfos.size() + 1;
			audioDeviceCursor = (audioDeviceCursor + 1) % total;
			repaint();
		}

		void enter() {
			selectedAudioMixerInfo = (audioDeviceCursor == 0) ? null : mixerInfos.get(audioDeviceCursor - 1);
			resetSynths();
			cardLayout.show(cardPanel, settingsCardKey);
			settingsPanel.repaint();
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.black);
			g.fill(getBounds());

			double uiScaleValue = settingsPanel.uiScaleValue;
			Font scaledFont = textFont.deriveFont((float)(textFont.getSize() * uiScaleValue / UI_SCALE));
			FontMetrics fm = getFontMetrics(scaledFont);
			g.setFont(scaledFont);

			int lineH = fm.getHeight();
			int x = 10;
			int y = lineH;

			g.setPaint(Color.WHITE);
			g.drawString("Output Device:", x, y);

			int indent = x + 12;
			for (int i = 0; i <= mixerInfos.size(); i++) {
				y += lineH;
				String name = (i == 0) ? "Default (system)" : mixerInfos.get(i - 1).getName();
				boolean isCursor = audioDeviceCursor == i;
				boolean isActive = (selectedAudioMixerInfo == null && i == 0)
						|| (selectedAudioMixerInfo != null && i > 0
								&& selectedAudioMixerInfo.getName().equals(mixerInfos.get(i - 1).getName()));

				if (isCursor) {
					g.setPaint(new Color(255, 255, 100));
					g.drawString("> " + name, indent, y);
				} else if (isActive) {
					g.setPaint(Color.WHITE);
					g.drawString("* " + name, indent, y);
				} else {
					g.setPaint(new Color(100, 100, 100));
					g.drawString("  " + name, indent, y);
				}
			}

			y += lineH * 2;
			g.setPaint(new Color(70, 70, 70));
			g.drawString("UP/DOWN: navigate   ENTER: select   ESC: back", x, y);
		}
	}

	class InstrumentSettingsPanel extends JPanel {
		StringCanvasConfig targetCanvas = null;
		int menuFocus = 0; // 0 = Soundfont File, 1 = Bank, 2 = Instrument

		public InstrumentSettingsPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape, "esc");
			actionMap.put("esc", rToA(this::esc));
			inputMap.put(k_Up, "up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down, "down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Left, "left");
			actionMap.put("left", rToA(this::left));
			inputMap.put(k_Right, "right");
			actionMap.put("right", rToA(this::right));
			inputMap.put(k_Enter, "enter");
			actionMap.put("enter", rToA(this::enter));
		}

		void prepare(StringCanvasConfig canvas) {
			targetCanvas = canvas;
			menuFocus = 0;
		}

		void esc() {
			if (targetCanvas != null) resetSynths();
			cardLayout.show(cardPanel, mainInterfaceCardKey);
		}

		void up() {
			menuFocus = Math.max(0, menuFocus - 1);
			repaint();
		}

		void down() {
			menuFocus = Math.min(2, menuFocus + 1);
			repaint();
		}

		void left() {
			if (targetCanvas == null) return;
			if (menuFocus == 1) {
				targetCanvas.setBank(Math.max(0, targetCanvas.getBank() - 1));
				playPreview();
				repaint();
			} else if (menuFocus == 2) {
				targetCanvas.setProgram(Math.max(0, targetCanvas.getProgram() - 1));
				playPreview();
				repaint();
			}
		}

		void right() {
			if (targetCanvas == null) return;
			if (menuFocus == 1) {
				targetCanvas.setBank(targetCanvas.getBank() + 1);
				playPreview();
				repaint();
			} else if (menuFocus == 2) {
				targetCanvas.setProgram(targetCanvas.getProgram() + 1);
				playPreview();
				repaint();
			}
		}

		void enter() {
			// Soundfont File (0) is a no-op for now; Bank (1) and Instrument (2) adjusted live
		}

		void playPreview() {
			resetSynths();
			try {
				Synthesizer synth = getSynth(targetCanvas, 0);
				MidiChannel channel = synth.getChannels()[0];
				channel.noteOn(60, 90);
				Thread t = new Thread(() -> {
					try { Thread.sleep(400); } catch (InterruptedException ignored) {}
					channel.noteOff(60);
				});
				t.setDaemon(true);
				t.start();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		@Override
		public void paint(Graphics g_) {
			if (targetCanvas == null) return;
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.black);
			g.fill(getBounds());

			double uiScaleValue = settingsPanel.uiScaleValue;
			Font scaledFont = textFont.deriveFont((float)(textFont.getSize() * uiScaleValue / UI_SCALE));
			FontMetrics fm = getFontMetrics(scaledFont);
			g.setFont(scaledFont);

			int lineH = fm.getHeight();
			int x = 10;
			int y = lineH;

			String title = "Instrument Settings: " + targetCanvas.getName();
			g.setPaint(Color.WHITE);
			g.drawString(title, x, y);

			y += lineH + 4;

			// Soundfont File row
			drawRow(g, fm, x, y, 0, "Soundfont File:",
				targetCanvas.getSoundfontFile().map(File::getName).orElse("Default"));

			y += lineH + 8;

			// Bank row (LEFT/RIGHT adjustable)
			drawRow(g, fm, x, y, 1, "Bank:",
				String.valueOf(targetCanvas.getBank()));

			y += lineH + 8;

			// Instrument row
			drawRow(g, fm, x, y, 2, "Instrument:",
				String.valueOf(targetCanvas.getProgram()));

			y += lineH * 2;
			g.setPaint(new Color(70, 70, 70));
			if (menuFocus == 1 || menuFocus == 2) {
				g.drawString("UP/DOWN: navigate   LEFT/RIGHT: adjust   ESC: apply & back", x, y);
			} else {
				g.drawString("UP/DOWN: navigate   ESC: apply & back", x, y);
			}
		}

		private void drawRow(Graphics2D g, FontMetrics fm, int x, int y, int itemIndex,
				String label, String value) {
			boolean focused = menuFocus == itemIndex;
			int labelW = fm.stringWidth(label);
			g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString(label, x, y);
			g.setPaint(focused ? new Color(255, 255, 100) : Color.GRAY);
			g.drawString(" " + value, x + labelW, y);
		}
	}

	class TextInputPanel extends JPanel {
		private final String label;
		private final java.util.function.Consumer<String> onConfirm;
		private final Runnable onCancel;
		private final StringBuffer buffer = new StringBuffer();

		TextInputPanel(String label,
		               java.util.function.Consumer<String> onConfirm,
		               Runnable onCancel) {
			this.label     = label;
			this.onConfirm = onConfirm;
			this.onCancel  = onCancel;

			InputMap  im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap am = getActionMap();

			im.put(k_Escape,    "esc");
			am.put("esc",       rToA(() -> { reset(); onCancel.run(); }));
			im.put(k_Enter,     "enter");
			am.put("enter",     rToA(this::confirm));
			im.put(k_Backspace, "backspace");
			am.put("backspace", rToA(this::backspace));
			im.put(k_Space,     "space");
			am.put("space",     rToA(() -> append(' ')));

			for (char c = 'A'; c <= 'Z'; c++) {
				String upper = String.valueOf(c);
				char   lower = upper.toLowerCase().charAt(0);
				char   c_    = c;
				im.put(KeyStroke.getKeyStroke(upper),            lower + "");
				im.put(KeyStroke.getKeyStroke("shift " + upper), upper);
				am.put(lower + "", rToA(() -> append(lower)));
				am.put(upper,      rToA(() -> append(c_)));
			}
			for (char c = '0'; c <= '9'; c++) {
				char c_ = c;
				im.put(KeyStroke.getKeyStroke("" + c), "" + c);
				am.put("" + c, rToA(() -> append(c_)));
			}
			for (char c : new char[]{'.', ',', '!', '?', '\'', '-', '_'}) {
				char c_ = c;
				im.put(KeyStroke.getKeyStroke(c), "" + c);
				am.put("" + c, rToA(() -> append(c_)));
			}
		}

		public void reset()            { buffer.setLength(0); repaint(); }
		public void reset(String text) { buffer.setLength(0); buffer.append(text); repaint(); }
		public String getText()        { return buffer.toString(); }

		private void append(char c) { buffer.append(c); repaint(); }
		private void backspace()    { if (buffer.length() > 0) { buffer.deleteCharAt(buffer.length()-1); repaint(); } }
		private void confirm()      { String t = buffer.toString(); reset(); onConfirm.accept(t); }

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fill(getBounds());

			int rowH = textFontMetrics.getMaxAscent();
			int y = rowH;

			g.setPaint(Color.WHITE);
			g.drawString(label, 2, y);
			y += rowH;

			int fieldW = (int)(getBounds().getWidth() - 4);
			g.setPaint(Color.DARK_GRAY);
			g.fillRect(2, y - rowH, fieldW, rowH);
			g.setPaint(new Color(220, 220, 255));
			g.drawString(buffer.toString() + "|", 4, y);
			y += rowH * 2;

			g.setPaint(Color.GRAY);
			g.drawString("Enter: confirm     Esc: cancel", 2, y);
		}
	}

	private static final Color HEADER_COLOR = new Color(255, 220, 80);
	private static final Color ENTRY_COLOR  = new Color(200, 200, 200);
	private static final Color KEY_COLOR    = new Color(120, 200, 255);
	private static final List<Pair<String,Color>> LINES;
	static {
		List<Pair<String,Color>> l = new ArrayList<>();
		l.add(new Pair<>("MENU BAR  (press , from grid to enter menu)", HEADER_COLOR));
		l.add(new Pair<>("  Left / Right          Navigate menu items", ENTRY_COLOR));
		l.add(new Pair<>("  Up / Down             Adjust value (TEMPO or SHUFFLE selected)", ENTRY_COLOR));
		l.add(new Pair<>("  Enter                 Activate selected item", ENTRY_COLOR));
		l.add(new Pair<>("  Space                 Play / Pause", ENTRY_COLOR));
		l.add(new Pair<>("  ,                     Return to grid", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("GRID  (press , from menu to enter grid)", HEADER_COLOR));
		l.add(new Pair<>("  Arrow keys            Move cursor", ENTRY_COLOR));
		l.add(new Pair<>("  Click                 Move cursor to cell", ENTRY_COLOR));
		l.add(new Pair<>("  " + (IS_MAC ? "Cmd" : "Ctrl") + " Click              Move playback position to cell", ENTRY_COLOR));
		l.add(new Pair<>("  Shift Left / Right    Jump to prev / next measure", ENTRY_COLOR));
		l.add(new Pair<>("  Shift Up / Down       Jump to first / last row", ENTRY_COLOR));
		l.add(new Pair<>("  Ctrl Shift Left/Right Jump to start / end of sequence", ENTRY_COLOR));
		l.add(new Pair<>("  " + (IS_MAC ? "Cmd" : "Alt") + " Left / Right          Move playback position", ENTRY_COLOR));
		l.add(new Pair<>("  " + (IS_MAC ? "Cmd" : "Alt") + " Shift Left / Right    Jump playback by measure", ENTRY_COLOR));
		l.add(new Pair<>("  A-Z, 0-9              Enter note at cursor", ENTRY_COLOR));
		l.add(new Pair<>("  - (hyphen)            Insert slide (string grids)", ENTRY_COLOR));
		l.add(new Pair<>("  Backspace             Delete note at cursor", ENTRY_COLOR));
		l.add(new Pair<>("  Delete                Clear cell (always)", ENTRY_COLOR));
		l.add(new Pair<>("  Home                  Jump to start", ENTRY_COLOR));
		l.add(new Pair<>("  End                   Jump to last event", ENTRY_COLOR));
		l.add(new Pair<>("  Page Up / Page Down   Scroll 80% of window", ENTRY_COLOR));
		l.add(new Pair<>("  Right-click           Paste clipboard", ENTRY_COLOR));
		l.add(new Pair<>("  " + (IS_MAC ? "Cmd" : "Ctrl") + " R                Set / clear repeat point", ENTRY_COLOR));
		l.add(new Pair<>("  Space                 Play / Pause", ENTRY_COLOR));
		l.add(new Pair<>("  ,                     Exit to menu bar", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("SELECTION  (" + (IS_MAC ? "Cmd" : "Ctrl") + "+L to begin)", HEADER_COLOR));
		l.add(new Pair<>("  " + (IS_MAC ? "Cmd" : "Ctrl") + " L                Start / end selection", ENTRY_COLOR));
		l.add(new Pair<>("  " + (IS_MAC ? "Cmd" : "Ctrl") + " C                Copy selection", ENTRY_COLOR));
		l.add(new Pair<>("  " + (IS_MAC ? "Cmd" : "Ctrl") + " X                Cut selection", ENTRY_COLOR));
		l.add(new Pair<>("  " + (IS_MAC ? "Cmd" : "Ctrl") + " V                Paste", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("NEW PROJECT SCREEN", HEADER_COLOR));
		l.add(new Pair<>("  Up / Down             Navigate fields", ENTRY_COLOR));
		l.add(new Pair<>("  A-Z                   Type song / artist name", ENTRY_COLOR));
		l.add(new Pair<>("  0-9                   Set canvas quantity", ENTRY_COLOR));
		l.add(new Pair<>("  Backspace             Delete character", ENTRY_COLOR));
		l.add(new Pair<>("  Enter                 Confirm (on last row)", ENTRY_COLOR));
		LINES = Collections.unmodifiableList(l);
	}

	class HelpPanel extends JPanel {

		private int scrollOffset = 0;

		public HelpPanel() {
			this.setFocusTraversalKeysEnabled(false);
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape,"esc");
			actionMap.put("esc", rToA(()->{ if (projectData != null) cardLayout.show(cardPanel, mainInterfaceCardKey); }));
			inputMap.put(k_Up,    "up");
			actionMap.put("up",   rToA(this::up));
			inputMap.put(k_Down,  "down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter, "back");
			actionMap.put("back", rToA(this::back));
			inputMap.put(k_Comma, "back2");
			actionMap.put("back2",rToA(this::back));
		}

		void up()   { if (scrollOffset > 0) { scrollOffset--; repaint(); } }
		void down() { if (scrollOffset < LINES.size()-1) { scrollOffset++; repaint(); } }
		void back() {
			scrollOffset = 0;
			cardLayout.show(cardPanel, mainInterfaceCardKey);
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fill(getBounds());

			int lineH = textFontMetrics.getMaxAscent();
			int y = lineH;

			// title bar
			g.setPaint(new Color(60, 60, 60));
			g.fillRect(0, 0, getWidth(), lineH + 4);
			g.setPaint(Color.WHITE);
			String title = "TABBYCAT  \u2014  HELP     (Enter or , to close)";
			g.drawString(title, 4, lineH);
			y += lineH + 4;

			for (int i = scrollOffset; i < LINES.size(); i++) {
				if (y + lineH > getHeight()) break;
				Pair<String,Color> line = LINES.get(i);
				g.setPaint(line.b);
				g.drawString(line.a, 4, y);
				y += lineH;
			}

			// scroll hint
			if (scrollOffset > 0 || scrollOffset + (getHeight() / lineH) < LINES.size()) {
				g.setPaint(new Color(100, 100, 100));
				String hint = "\u2191\u2193 scroll";
				g.drawString(hint, getWidth() - textFontMetrics.stringWidth(hint) - 4, lineH);
			}
		}
	}
}
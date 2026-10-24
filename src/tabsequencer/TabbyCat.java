package tabsequencer;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
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
import java.util.TreeSet;
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
import tabsequencer.config.EventCanvasConfig;
import tabsequencer.config.PercRowType;
import tabsequencer.config.PercToken;
import tabsequencer.config.ProjectFileData;
import tabsequencer.config.StringCanvasConfig;
import tabsequencer.events.ControlEvent;
import tabsequencer.events.ControlEventType;
import tabsequencer.events.ProgramChange;
import tabsequencer.events.TuningChangeEvent;
import tabsequencer.events.TupletEvent;
import tabsequencer.events.StickyNote;
import tabsequencer.events.ShuffleEvent;
import tabsequencer.events.TempoEvent;
import tabsequencer.events.TimeSignatureDenominator;
import tabsequencer.events.TimeSignatureEvent;
import java.util.ArrayDeque;

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
	static final String mainInterfaceCardKey = "MAIN INTERFACE";
	static final String saveProjectCardKey = "SAVE PROJECT";
	static final String helpCardKey = "HELP";
	static final String settingsCardKey = "SETTINGS";
	static final String audioOutputCardKey = "AUDIO OUTPUT";
	static final String uiSettingsCardKey  = "UI SETTINGS";
	static final String instrumentSettingsCardKey = "INSTRUMENT SETTINGS EDITOR";
	static final String stringEditorCardKey = "STRING EDITOR";
	static final String additionalFretEditorCardKey = "ADDITIONAL FRET EDITOR";
	static final String drumEditorCardKey = "DRUM EDITOR";
	static final String eventCanvasSettingsCardKey = "EVENT CANVAS SETTINGS";
	static final String deleteConfirmCardKey = "DELETE CONFIRM";
	static final String newCanvasCardKey = "NEW CANVAS";

	static final double MIDDLE_C = 220.0 * Math.pow(2d, 3.0 / 12.0);
	int trackStripWidth = 30;
	static final int UI_SCALE = 2;
	double displayScale = UI_SCALE;
	int playbackRenderIntervalMs = 20;
	double rainbowBrightness = 1.0;
	double scrollbarBrightness = 1.0;
	private static final float[] rbFractions = new float[]{0f, 1f/6, 2f/6, 3f/6, 4f/6, 5f/6, 1f};
	private Color[] rbColors = buildRbColors();
	private BufferedImage scrollbarRainbowImage = buildScrollbarRainbow();
	private final BufferedImage oliveTileScrollbar = buildOliveTile(200);
	private final BufferedImage oliveTileGrid      = buildOliveTile(160);
	private java.awt.LinearGradientPaint cachedRainbowPaint = null;
	private float cachedRainbowPaintW = -1;
	private double cachedRainbowPaintBrightness = Double.NaN;
	private java.awt.LinearGradientPaint cachedHintRainbow = null;
	private float cachedHintRainbowW = -1;
	private double cachedHintRainbowBrightness = Double.NaN;
	// Cached TexturePaint wrappers — tile anchored at origin; uniform texture so phase is irrelevant
	private final java.awt.TexturePaint olivePaintScrollbar = new java.awt.TexturePaint(oliveTileScrollbar, new Rectangle2D.Double(0, 0, 20, 20));
	private final java.awt.TexturePaint olivePaintGrid      = new java.awt.TexturePaint(oliveTileGrid,      new Rectangle2D.Double(0, 0, 20, 20));
	// Static stroke instances
	private static final java.awt.BasicStroke STROKE_1 = new java.awt.BasicStroke(1);
	private static final java.awt.BasicStroke STROKE_2 = new java.awt.BasicStroke(2);
	private static final java.awt.BasicStroke STROKE_3 = new java.awt.BasicStroke(3);
	// Static color constants — prevents per-frame allocation inside paint loops
	private static final Color C_TUPLET_BG      = new Color(32,  120, 100);
	private static final Color C_TUPLET_BG_SEL  = new Color(32,  120, 100, 120);
	private static final Color C_TUPLET_MARKER  = new Color(80,  200, 160, 160);
	private static final Color C_PROGRAM_CHANGE = new Color(100, 200, 255);
	private static final Color C_TEMPO          = new Color(255, 200, 100);
	private static final Color C_SHUFFLE_EV     = new Color(100, 220, 255);
	private static final Color C_STICKY_NOTE    = new Color(255, 220, 80);
	private static final Color C_TUNING_CHANGE  = new Color(200, 100, 255);
	private static final Color C_PLAYBACK_LINE  = new Color(220, 220, 80);
	private static final Color C_BEAT_MARKER    = new Color(180, 180, 180, 100);
	private static final Color C_MEASURE_LINE   = Color.getHSBColor(0.85f, 0.25f, 1f);
	// Scrollbar event marker colors
	private static final Color C_SB_TEMPO          = new Color(255, 80,  80);
	private static final Color C_SB_TIME_SIG        = new Color(80,  180, 255);
	private static final Color C_SB_SHUFFLE         = new Color(255, 200, 50);
	private static final Color C_SB_PROGRAM_CHANGE  = new Color(120, 255, 120);
	private static final Color C_SB_TUNING_CHANGE   = new Color(200, 100, 255);
	private static final Color C_SB_STICKY_NOTE     = new Color(0xFF, 0xFF, 0x00);

	private Color[] buildRbColors() {
		Color[] c = new Color[7];
		for (int i = 0; i < 7; i++) c[i] = Color.getHSBColor(i / 6f, 0.5f, (float) rainbowBrightness);
		return c;
	}
	private BufferedImage buildScrollbarRainbow() {
		BufferedImage img = new BufferedImage(7, 1, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < 7; i++) img.setRGB(i, 0, Color.getHSBColor(i / 6f, 0.5f, (float) scrollbarBrightness).getRGB());
		return img;
	}
	private static BufferedImage buildOliveTile(int alpha) {
		int sz = 20;
		BufferedImage tile = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
		for (int py = 0; py < sz; py++) {
			for (int px = 0; px < sz; px++) {
				double d = ((px + py) % sz) / (double) sz;
				double tv = (1.0 - Math.cos(d * 2 * Math.PI)) / 2.0;
				int cr = (int)(96 + tv * 53);
				int cg = (int)(96 + tv * 53);
				int cb = (int)(24 + tv * 26);
				tile.setRGB(px, py, new Color(cr, cg, cb, alpha).getRGB());
			}
		}
		return tile;
	}
	private java.awt.LinearGradientPaint getRainbowPaint(float w) {
		if (cachedRainbowPaint == null || cachedRainbowPaintW != w || cachedRainbowPaintBrightness != rainbowBrightness) {
			cachedRainbowPaint = new java.awt.LinearGradientPaint(trackStripWidth, 0, w, 0, rbFractions, rbColors);
			cachedRainbowPaintW = w;
			cachedRainbowPaintBrightness = rainbowBrightness;
		}
		return cachedRainbowPaint;
	}
	private java.awt.LinearGradientPaint getHintRainbow(float w) {
		if (cachedHintRainbow == null || cachedHintRainbowW != w || cachedHintRainbowBrightness != rainbowBrightness) {
			Color[] off = new Color[7];
			for (int i = 0; i < 7; i++) off[i] = Color.getHSBColor((i / 6f + 0.5f) % 1f, 0.5f, (float) rainbowBrightness);
			cachedHintRainbow = new java.awt.LinearGradientPaint(0, 0, w, 0, rbFractions, off);
			cachedHintRainbowW = w;
			cachedHintRainbowBrightness = rainbowBrightness;
		}
		return cachedHintRainbow;
	}
	static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");
	
	private ProjectFileData projectData = null;
	private CanvasesConfig canvasesConfig = CanvasesConfig.getXMLInstance();
	String gridFontFamily = "Monospaced";
	int gridFontSize = 12;
	private Font gridFont = new Font(gridFontFamily, Font.PLAIN, gridFontSize);
	private Font italicGridFont = gridFont.deriveFont(Font.ITALIC);
	private FontMetrics gridFontMetrics = new Canvas().getFontMetrics(gridFont);
	String textFontFamily = "Monospaced";
	int textFontSize = 14;
	private Font textFont = new Font(textFontFamily, Font.PLAIN, textFontSize);
	private FontMetrics textFontMetrics = new Canvas().getFontMetrics(textFont);
	
	final int scrollTimeMargin = 4;	

	FileFilter fileFilter = new FileNameExtensionFilter(".meow files (.meow)", "meow");
	final File defaultProjectPath = new File("scores");
	final File recoveryFile = new File("recovery.meow");
	
	List<File> sf2Files = new ArrayList<>();
	Map<File,Soundbank> loadedSoundbanks = new HashMap<>();
	Map<DrumCanvasConfig,Synthesizer> drumSynths = new HashMap<>();
	Map<Pair<StringCanvasConfig,Integer>,Synthesizer> stringSynths = new HashMap<>();
	volatile Mixer.Info selectedAudioMixerInfo = null;
	MidiChannel lastPreviewChannel = null;
	int lastPreviewNote = -1;
	
	final AtomicReference<File> activeFile = new AtomicReference<>(null);
	final AtomicBoolean fileHasBeenModified = new AtomicBoolean(false);
	private final ArrayDeque<GridSnapshot> undoStack = new ArrayDeque<>();
	private final ArrayDeque<GridSnapshot> redoStack = new ArrayDeque<>();
	private static final int MAX_UNDO_DEPTH = 100;
	InlineEntryPanel activeInlinePanel = null;
	boolean swallowNextTyped = false;

	final AtomicBoolean isPlaying = new AtomicBoolean(false);
	final AtomicBoolean playbackDaemonIsStarted = new AtomicBoolean(false);
	final java.util.concurrent.atomic.AtomicLong playbackRestartNanos = new java.util.concurrent.atomic.AtomicLong(0);

	volatile long smoothTickStartNanos = 0L;
	volatile long smoothTickDurationNanos = 1_000_000L; // 1ms default, avoid divide-by-zero
	volatile int smoothTickBase = 0;

	final TreeMap<Integer, Integer> cachedMeasurePositions = new TreeMap<>(); // tick → measure# (navigation only)
	final TreeMap<Double, Integer> cachedMeasureCells = new TreeMap<>();    // real-time cell → measure# (rendering)
	final ArrayList<Double> cachedBeatMarkerCells = new ArrayList<>();      // real-time cell positions (rendering)
	final TreeMap<Integer, TupletEvent> cachedTuplets = new TreeMap<>();
	private volatile int cachedAutoRepeatEnd = 1;
	// cached tick timing — recomputed only when BPM or shuffle changes
	private long cachedBpmForNanos      = -1;
	private int  cachedShuffleForNanos  = -1;
	private long cachedSixteenthNanosBase = 0;
	private long cachedSixteenthNanosEven = 0;
	private long cachedSixteenthNanosOdd  = 0;

	final ExecutorService playbackDaemon = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "playback-daemon");
		t.setPriority(Thread.MAX_PRIORITY);
		t.setDaemon(true);
		return t;
	});
	final ScheduledExecutorService midiDaemon = Executors.newSingleThreadScheduledExecutor();

	final JFrame frame = new JFrame("TabbyCat");
		
	private TimeSignatureEventPanel timeSignatureEventPanel;
	private TempoEventPanel tempoEventPanel;
	private ShuffleEventPanel shuffleEventPanel;
	private TextInputPanel notesEventPanel;
	private ProgramChangeEventPanel programChangeEventPanel;
	private TuningChangeEventPanel tuningChangeEventPanel;
	private TupletEventPanel tupletEventPanel;
	private LoadProjectPanel loadProjectPanel;
	private MainInterfacePanel mainInterfacePanel;
	private HelpPanel helpPanel;
	private SaveProjectPanel saveProjectPanel;
	private SettingsPanel settingsPanel;
	private AudioOutputPanel audioOutputPanel;
	private UISettingsPanel  uiSettingsPanel;
	private InstrumentSettingsPanel instrumentSettingsPanel;
	private EventCanvasSettingsPanel eventCanvasSettingsPanel;
	private DeleteConfirmPanel deleteConfirmPanel;
	private NewCanvasPanel newCanvasPanel;
	private StringEditorPanel stringEditorPanel;
	private AdditionalFretEditorPanel additionalFretEditorPanel;
	private DrumEditorPanel drumEditorPanel;
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
	KeyStroke k_CmdBracketLeft  = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " OPEN_BRACKET");
	KeyStroke k_CmdBracketRight = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " CLOSE_BRACKET");
	KeyStroke k_CtrlS = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " S");
	KeyStroke k_CtrlShiftS = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " shift S");
	KeyStroke k_CtrlI = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " I");
	KeyStroke k_CtrlD  = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " D");
	KeyStroke k_CtrlE  = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " E");
	KeyStroke k_AltUp   = KeyStroke.getKeyStroke("alt UP");
	KeyStroke k_AltDown = KeyStroke.getKeyStroke("alt DOWN");
	KeyStroke k_ModeNote   = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " shift N");
	KeyStroke k_ModeVel    = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " shift V");
	KeyStroke k_ModePan    = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " shift P");
	KeyStroke k_ModeOffset = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " shift T");
	KeyStroke k_CtrlZ = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " Z");
	KeyStroke k_CtrlR = KeyStroke.getKeyStroke((IS_MAC ? "meta" : "ctrl") + " R");
	
	KeyStroke k_Space      = KeyStroke.getKeyStroke("SPACE");
	KeyStroke k_ShiftSpace = KeyStroke.getKeyStroke("shift SPACE");

	KeyStroke k_Delete       = KeyStroke.getKeyStroke("DELETE");
	KeyStroke k_Home         = KeyStroke.getKeyStroke("HOME");
	KeyStroke k_End          = KeyStroke.getKeyStroke("END");
	KeyStroke k_PageUp       = KeyStroke.getKeyStroke("PAGE_UP");
	KeyStroke k_PageDown     = KeyStroke.getKeyStroke("PAGE_DOWN");

	

	static String fmtFloat(double v) {
		return String.format("%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	int getLastContentT() {
		int max = 0;
		for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
			if (c instanceof EventCanvasConfig) {
				for (java.awt.Point p : projectData.getEventData(c.getId()).keySet()) {
					if (p.x > max) max = p.x;
				}
			}
		}
		for (InstrumentDataKey k : projectData.getInstrumentData().keySet()) {
			if (k.getTime() > max) max = k.getTime();
		}
		Integer next1 = cachedMeasurePositions.higherKey(max);
		if (next1 == null) return max + 1;
		Integer next2 = cachedMeasurePositions.higherKey(next1);
		return (next2 != null) ? next2 : next1;
	}

	void updateAutoRepeatEnd() {
		if (projectData == null) { cachedAutoRepeatEnd = 1; return; }
		cachedAutoRepeatEnd = getLastContentT();
	}

	void playbackDaemonFunction(long intendedFireTimeNanos) {
		// DO NOT CALL THIS ON MASTER THREAD — runs as a persistent loop
		while (true) {
			if (!isPlaying.get()) {
				LockSupport.parkNanos(1_000_000L); // 1ms nap — don't spin 100%
				continue;
			}
			// Reset timer on explicit restart, or if stale (e.g. > 2s gap)
			long restartSignal = playbackRestartNanos.getAndSet(0);
			if (restartSignal > 0 || System.nanoTime() - intendedFireTimeNanos > 2_000_000_000L) {
				intendedFireTimeNanos = System.nanoTime();
			}
			try {
				if (projectData.getPlaybackT().get() == 0) {
					projectData.getTempo().set(projectData.getInitialTempo().get());
				}
				final long thisTickFireTime = intendedFireTimeNanos;
				handleProgramEvents(thisTickFireTime);
				SwingUtilities.invokeLater(() -> mainInterfacePanel.repaint());
			} catch (Exception e) {
				e.printStackTrace();
			}
			int tBeforeIncrement = projectData.getPlaybackT().getAndUpdate(i -> {
				int r0 = projectData.getRepeatT0().get();
				int r1 = projectData.getRepeatT1().get();
				int loopStart = (r0 >= 0) ? r0 : 0;
				int loopEnd   = (r1 >= 0) ? r1 : cachedAutoRepeatEnd;
				return (loopEnd > loopStart && i + 1 >= loopEnd) ? loopStart : i + 1;
			});
			double deviceScale_ = mainInterfacePanel.getGraphicsConfiguration().getDefaultTransform().getScaleX();
			int visibleCols = (int)(mainInterfacePanel.getWidth() * deviceScale_ / displayScale / mainInterfacePanel.getCellWidth());
			int cursorCol = visibleCols / 4;
			if (mainInterfacePanel.followPlayback) {
				projectData.getViewT().set(Math.max(0, projectData.getPlaybackT().get() - cursorCol));
			}
			long bpm = projectData.getTempo().get();
			int shuffle = projectData.getShuffle().get();
			if (bpm != cachedBpmForNanos || shuffle != cachedShuffleForNanos) {
				long base = 60_000_000_000L / bpm / 4;
				double ratio = shuffle / 100.0;
				cachedSixteenthNanosBase = base;
				cachedSixteenthNanosEven = (long)(base * (1.0 + ratio));
				cachedSixteenthNanosOdd  = (long)(base * (1.0 - ratio));
				cachedBpmForNanos    = bpm;
				cachedShuffleForNanos = shuffle;
			}
			long thisSixteenthNanos = tBeforeIncrement % 2 == 0
					? cachedSixteenthNanosEven : cachedSixteenthNanosOdd;
			// Tuplet override: use raw (un-shuffled) base nanos
			TupletEvent activeTuplet = getTupletAtClock(tBeforeIncrement);
			if (activeTuplet != null) {
				Integer tuplT0 = cachedTuplets.floorKey(tBeforeIncrement);
				int rangeWidth = activeTuplet.getEndT() - tuplT0;
				int k = tBeforeIncrement - tuplT0;
				if (k < activeTuplet.getSubdivisions()) {
					thisSixteenthNanos = (long)((double) rangeWidth * cachedSixteenthNanosBase / activeTuplet.getSubdivisions());
				}
				// k >= B: normal clock tick within the tuplet's clock range — leave thisSixteenthNanos unchanged
			}
			smoothTickBase = tBeforeIncrement;
			smoothTickStartNanos = intendedFireTimeNanos;
			smoothTickDurationNanos = Math.max(1L, thisSixteenthNanos);
			long nextIntendedNanos = intendedFireTimeNanos + thisSixteenthNanos;
			// Hybrid: park until 2ms before target, then spin for sub-ms precision
			if (thisSixteenthNanos > 0) {
				long sleepNanos = nextIntendedNanos - System.nanoTime() - 2_000_000L;
				if (sleepNanos > 0) {
					LockSupport.parkNanos(sleepNanos);
				}
				while (System.nanoTime() < nextIntendedNanos) { /* spin */ }
			}
			intendedFireTimeNanos = nextIntendedNanos;
		}
	}	
	
	Map<String,Pair<MidiChannel,Integer>> activeDrumNotes = new HashMap<>();
	Map<MidiChannel,Integer> activeStringNotes = new HashMap<>();

	void rescanProgramsToT(int t) {
		for (CanvasConfig canvas : projectData.getCanvases().getCanvases()) {
			sendProgramChange(canvas, canvas.getBank(), canvas.getProgram());
		}
		Map<String, int[]> last = new HashMap<>(); // canvasName -> [bank, program, time]
		for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
			if (!(c instanceof EventCanvasConfig)) continue;
			for (Map.Entry<Point, ControlEvent> entry : projectData.getEventData(c.getId()).entrySet()) {
				int et = entry.getKey().x;
				if (et <= t && entry.getValue().getType() == ControlEventType.PROGRAM_CHANGE) {
					ProgramChange pc = (ProgramChange) entry.getValue();
					String name = pc.getInstrument();
					if (!last.containsKey(name) || et >= last.get(name)[2]) {
						last.put(name, new int[]{pc.getBank(), pc.getProgram(), et});
					}
				}
			}
		}
		for (Map.Entry<String, int[]> entry : last.entrySet()) {
			projectData.getCanvasConfig(entry.getKey()).ifPresent(canvas ->
				sendProgramChange(canvas, entry.getValue()[0], entry.getValue()[1]));
		}
	}

	void rescanTuningToT(int t) {
		for (CanvasConfig canvas : projectData.getCanvases().getCanvases()) {
			if (canvas instanceof StringCanvasConfig)
				((StringCanvasConfig) canvas).clearRuntimeEdoStepOffsets();
		}
		// key: canvasName + ":" + row -> [stepOffset, time]
		Map<String, double[]> last = new HashMap<>();
		for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
			if (!(c instanceof EventCanvasConfig)) continue;
			for (Map.Entry<Point, ControlEvent> entry : projectData.getEventData(c.getId()).entrySet()) {
				int et = entry.getKey().x;
				if (et <= t && entry.getValue().getType() == ControlEventType.TUNING_CHANGE) {
					TuningChangeEvent tc = (TuningChangeEvent) entry.getValue();
					String key = tc.getCanvasName() + ":" + tc.getRow();
					if (!last.containsKey(key) || et >= last.get(key)[1]) {
						last.put(key, new double[]{tc.getStepOffset(), et});
					}
				}
			}
		}
		for (Map.Entry<String, double[]> entry : last.entrySet()) {
			String[] parts = entry.getKey().split(":");
			String canvasName = parts[0];
			int row = Integer.parseInt(parts[1]);
			projectData.getCanvasConfig(canvasName).ifPresent(canvas -> {
				if (canvas instanceof StringCanvasConfig)
					((StringCanvasConfig) canvas).setRuntimeEdoStepOffset(row, entry.getValue()[0]);
			});
		}
	}

	void sendProgramChange(CanvasConfig canvas, int bank, int program) {
		try {
			if (canvas instanceof StringCanvasConfig) {
				StringCanvasConfig sc = (StringCanvasConfig) canvas;
				for (Map.Entry<Pair<StringCanvasConfig,Integer>, Synthesizer> entry : stringSynths.entrySet()) {
					if (entry.getKey().a == sc) {
						Synthesizer synth = entry.getValue();
						if (sc.getSoundfontFile().isPresent()) {
							Soundbank sb = loadedSoundbanks.get(sc.getSoundfontFile().get());
							if (sb != null) {
								for (Instrument inst : sb.getInstruments()) {
									if (inst.getPatch().getBank() == bank && inst.getPatch().getProgram() == program) {
										synth.loadInstrument(inst);
										break;
									}
								}
							}
						}
						for (int i : new int[]{0,1,2,3,4,5,6,7,8,10,11,12,13,14,15}) {
							synth.getChannels()[i].programChange(bank, program);
						}
					}
				}
			} else if (canvas instanceof DrumCanvasConfig) {
				DrumCanvasConfig dc = (DrumCanvasConfig) canvas;
				if (drumSynths.containsKey(dc)) {
					Synthesizer synth = drumSynths.get(dc);
					if (dc.getSoundfontFile().isPresent()) {
						Soundbank sb = loadedSoundbanks.get(dc.getSoundfontFile().get());
						if (sb != null) {
							for (Instrument inst : sb.getInstruments()) {
								if (inst.getPatch().getBank() == bank && inst.getPatch().getProgram() == program) {
									synth.loadInstrument(inst);
									break;
								}
							}
						}
					}
					synth.getChannels()[9].programChange(bank, program);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	void handleProgramEvents(long tickFireTimeNanos) {
		int t = projectData.getPlaybackT().get();
		if (t == 0) {
			rescanProgramsToT(0);
			rescanTuningToT(0);
		}
		for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
			if (!(c instanceof EventCanvasConfig)) continue;
			for (Map.Entry<Point, ControlEvent> e : projectData.getEventData(c.getId()).entrySet()) {
				if (e.getKey().x != t) continue;
				switch (e.getValue().getType()) {
				case TEMPO:
					projectData.getTempo().set(((TempoEvent) e.getValue()).getTempo());
					break;
				case SHUFFLE:
					projectData.getShuffle().set(((ShuffleEvent) e.getValue()).getShuffle());
					break;
				case PROGRAM_CHANGE: {
					ProgramChange pc = (ProgramChange) e.getValue();
					projectData.getCanvasConfig(pc.getInstrument()).ifPresent(canvas ->
						sendProgramChange(canvas, pc.getBank(), pc.getProgram()));
					break;
				}
				case TUNING_CHANGE: {
					TuningChangeEvent tc = (TuningChangeEvent) e.getValue();
					projectData.getCanvasConfig(tc.getCanvasName()).ifPresent(canvas -> {
						if (canvas instanceof StringCanvasConfig)
							((StringCanvasConfig) canvas).setRuntimeEdoStepOffset(tc.getRow(), tc.getStepOffset());
					});
					break;
				}
				default:
					break;
				}
			}
		}
		// drum noteOffs are now handled per-row at note-on time (choke) and on stopPlayback()
		for (CanvasConfig canvas :projectData.getCanvases().getCanvases()) {
			if (canvas instanceof StringCanvasConfig) {
				StringCanvasConfig stringCanvas = (StringCanvasConfig) canvas; 
				for (int row = 0; row < stringCanvas.getRowCount(); row++) {
					InstrumentDataKey key = new InstrumentDataKey(stringCanvas.getId(),t,row);
					NoteCell valCell = projectData.getInstrumentData().get(key);
					String val = valCell != null ? valCell.token : null;
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
			NoteCell noteCell = e.getValue();
			String value = noteCell.token;
			projectData.getCanvasConfigById(dataKey.getInstrumentId()).ifPresent(canvasConfig -> {
				if (canvasConfig.isMuted()) return;
				Runnable fireNote = null;
				if (canvasConfig instanceof DrumCanvasConfig) {
					DrumCanvasConfig drumConfig = (DrumCanvasConfig) canvasConfig;
					int resolvedMidi = -1;
					try { resolvedMidi = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
					if (resolvedMidi < 0 || resolvedMidi > 127) resolvedMidi = drumConfig.getMidiNumber(value).orElse(-1);
					if (resolvedMidi >= 0) {
						final int midiNumber = resolvedMidi;
						fireNote = () -> {
							try {
								Synthesizer synth = getSynth(drumConfig);
								MidiChannel ch = synth.getChannels()[9];
								String rowKey = drumConfig.getId() + ":" + dataKey.getRow();
								Pair<MidiChannel,Integer> prev = activeDrumNotes.get(rowKey);
								if (prev != null) prev.a.noteOff(prev.b);
								ch.controlChange(10, noteCell.pan);
								ch.noteOn(midiNumber, noteCell.velocity);
								activeDrumNotes.put(rowKey, new Pair<>(ch, midiNumber));
							} catch (Exception e1) {
								e1.printStackTrace();
							}
						};
					}
				} else {
					StringCanvasConfig guitarConfig = (StringCanvasConfig) canvasConfig;
					if (value.charAt(0) != '-') {
						fireNote = () -> guitarConfig.getFrequency(value, dataKey.getRow()).ifPresent(freq -> {
							try {
								int channelNum = dataKey.getRow()%15 >= 9 ?
										dataKey.getRow()%15+1:dataKey.getRow()%15;
								Synthesizer synth = getSynth(guitarConfig, dataKey.getRow());
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
									channel.controlChange(10, noteCell.pan);
									channel.noteOn(midiNote, noteCell.velocity);
									activeStringNotes.put(channel, midiNote);
								} catch (Exception ex) {
									ex.printStackTrace();
								}
							} catch (Exception ex) {
								ex.printStackTrace();
							}
						});
					}
				}
				if (fireNote == null) return;
				long noteFireNanos = tickFireTimeNanos + (long)noteCell.offsetMs * 1_000_000L;
				long delayNanos = Math.max(0L, noteFireNanos - System.nanoTime());
				midiDaemon.schedule(fireNote, delayNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
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
				Soundbank defaultSB = synth.getDefaultSoundbank();
				if (defaultSB != null) synth.unloadAllInstruments(defaultSB);
				synth.loadAllInstruments(soundbank);
				synth.getChannels()[9].programChange(config.getBank(), config.getProgram());
			}

			// Zero reverb and chorus sends on channel 9 — Gervill defaults are non-zero
			synth.getChannels()[9].controlChange(91, 0); // reverb
			synth.getChannels()[9].controlChange(93, 0); // chorus
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

	void playFromStart() {
		stopPlayback();
		projectData.getPlaybackT().set(0);
		startPlayback();
	}
	

	long lastStopTimeMs = 0;

	javax.swing.Timer playbackRenderTimer = null;

	void startPlayback() {
		if (!playbackDaemonIsStarted.get()) {
			playbackDaemonIsStarted.set(true);
			playbackDaemon.submit(() -> playbackDaemonFunction(System.nanoTime()));
		}
		int startT = projectData.getPlaybackT0().get();
		projectData.getPlaybackT().set(startT);
		midiDaemon.submit(() -> rescanProgramsToT(startT));
		playbackRestartNanos.set(System.nanoTime());
		isPlaying.set(true);
		if (playbackRenderTimer != null) playbackRenderTimer.stop();
		playbackRenderTimer = new javax.swing.Timer(playbackRenderIntervalMs, e -> {
			if (!isPlaying.get()) {
				((javax.swing.Timer)e.getSource()).stop();
				playbackRenderTimer = null;
			}
			mainInterfacePanel.repaint();
		});
		playbackRenderTimer.start();
	}

	void stopPlayback() {
		isPlaying.set(false);
		lastStopTimeMs = System.currentTimeMillis();
		activeDrumNotes.forEach((rowKey, pair) -> pair.a.noteOff(pair.b));
		activeDrumNotes.clear();
		activeStringNotes.forEach((channel, note) -> channel.noteOff(note));
		activeStringNotes.clear();
		if (projectData != null) {
			for (CanvasConfig c : projectData.getCanvases().getCanvases())
				if (c instanceof StringCanvasConfig)
					((StringCanvasConfig) c).clearRuntimeEdoStepOffsets();
		}
		if (playbackRenderTimer != null) { playbackRenderTimer.stop(); playbackRenderTimer = null; }
		if (projectData != null) {
			int visibleTicks = mainInterfacePanel.lastT1 - projectData.getViewT().get();
			int cursorCol = visibleTicks / 4;
			projectData.getViewT().set(Math.max(0, projectData.getPlaybackT0().get() - cursorCol));
			SwingUtilities.invokeLater(() -> mainInterfacePanel.repaint());
		}
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

	void warmupSynths() {
		if (projectData == null) return;
		List<CanvasConfig> canvases = new java.util.ArrayList<>(projectData.getCanvases().getCanvases());
		Thread wt = new Thread(() -> {
			for (CanvasConfig c : canvases) {
				try {
					if (c instanceof StringCanvasConfig) {
						StringCanvasConfig sc = (StringCanvasConfig) c;
						for (int row = 0; row < sc.getRowCount(); row++) {
							Synthesizer synth = getSynth(sc, row);
							MidiChannel ch = synth.getChannels()[0];
							ch.noteOn(60, 1);
							ch.noteOff(60);
						}
					} else if (c instanceof DrumCanvasConfig) {
						Synthesizer synth = getSynth((DrumCanvasConfig) c);
						MidiChannel ch = synth.getChannels()[9];
						ch.noteOn(36, 1);
						ch.noteOff(36);
					}
				} catch (Exception ignored) {}
			}
		}, "synth-warmup");
		wt.setDaemon(true);
		wt.start();
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
				int midiNumber = -1;
				try { midiNumber = Integer.parseInt(token); } catch (NumberFormatException ignored) {}
				if (midiNumber < 0 || midiNumber > 127) midiNumber = drumConfig.getMidiNumber(token).orElse(-1);
				if (midiNumber >= 0) {
					final int mn = midiNumber;
					try {
						Synthesizer synth = getSynth(drumConfig);
						MidiChannel channel = synth.getChannels()[9];
						channel.noteOn(mn, 90);
						lastPreviewChannel = channel;
						lastPreviewNote = mn;
						Thread t = new Thread(() -> {
							try { Thread.sleep(350); } catch (InterruptedException ignored) {}
							channel.noteOff(mn);
						});
						t.setDaemon(true);
						t.start();
					} catch (Exception ex) { ex.printStackTrace(); }
				}
			}
		} catch (Exception ex) { ex.printStackTrace(); }
	}
	
	public void updateMeasureLinePositions() {
		TimeSignatureEvent ts = new TimeSignatureEvent(4, TimeSignatureDenominator._4);
		double beatLen    = 16.0 / ts.denominator.getValue();
		double measureLen = (double) ts.get16ths();

		Map<Integer, Integer> measures = new TreeMap<>();
		TreeMap<Double, Integer> mCells = new TreeMap<>();
		ArrayList<Double> bCells = new ArrayList<>();

		measures.put(0, 1);
		mCells.put(0.0, 1);
		int measureNum = 2;

		double rt      = 0.0;  // accumulated real time
		double nextBeat    = beatLen;
		double nextMeasure = measureLen;

		for (int t = 0; t < 10000 * 16; t++) {
			// --- time-signature check ---
			TimeSignatureEvent newTs = null;
			outer:
			for (CanvasConfig c_ : projectData.getCanvases().getCanvases()) {
				if (!(c_ instanceof EventCanvasConfig)) continue;
				Map<Point, ControlEvent> evData_ = projectData.getEventData(c_.getId());
				for (int row_ = 0; row_ < c_.getRowCount(); row_++) {
					ControlEvent ev_ = evData_.get(new Point(t, row_));
					if (ev_ != null && ev_.getType() == ControlEventType.TIME_SIGNATURE) {
						newTs = (TimeSignatureEvent) ev_;
						break outer;
					}
				}
			}
			if (newTs != null) {
				ts = newTs;
				beatLen    = 16.0 / ts.denominator.getValue();
				measureLen = (double) ts.get16ths();
				if (t > 0) {
					measures.put(t, measureNum);
					mCells.put((double) t, measureNum++);
				}
				nextBeat    = rt + beatLen;
				nextMeasure = rt + measureLen;
			} else {
				// Exact boundary at the start of this tick?
				if (rt > 1e-9) {
					if (Math.abs(rt - nextMeasure) < 1e-9) {
						measures.put(t, measureNum);
						mCells.put((double) t, measureNum++);
						nextMeasure += measureLen;
						nextBeat = rt + beatLen; // realign beats to measure start
					} else if (Math.abs(rt - nextBeat) < 1e-9) {
						bCells.add((double) t);
						nextBeat += beatLen;
					}
				}
			}

			// --- real-time step for this tick ---
			double step = 1.0;
			Integer tupT0 = cachedTuplets.floorKey(t);
			if (tupT0 != null) {
				TupletEvent te = cachedTuplets.get(tupT0);
				int A = te.getEndT() - tupT0;
				int B = te.getSubdivisions();
				if (t < tupT0 + Math.max(A, B)) {
					int k = t - tupT0;
					if (k < B) {
						step = (double) A / B;
					}
					// k >= B (only when B < A): clock-only tick, step stays 1.0
				}
			}

			// Fractional crossings strictly inside (rt, rt+step)
			// (can happen when step > beatLen, e.g. large tuplet with small beat)
			while (nextBeat > rt + 1e-9 && nextBeat < rt + step - 1e-9) {
				double frac = (nextBeat - rt) / step;
				double cell = t + frac;
				if (Math.abs(nextBeat - nextMeasure) < 1e-9) {
					measures.put((int) Math.round(cell), measureNum);
					mCells.put(cell, measureNum++);
					nextMeasure += measureLen;
					nextBeat    += beatLen;
				} else {
					bCells.add(cell);
					nextBeat += beatLen;
				}
			}

			rt += step;
		}

		cachedMeasurePositions.clear();
		cachedMeasurePositions.putAll(measures);
		cachedMeasureCells.clear();
		cachedMeasureCells.putAll(mCells);
		cachedBeatMarkerCells.clear();
		cachedBeatMarkerCells.addAll(bCells);
		updateAutoRepeatEnd();
	}

	private GridSnapshot snapshot() {
		return new GridSnapshot(projectData.getEventDataByCanvas(), projectData.getInstrumentData());
	}

	private void pushUndo() {
		if (projectData == null) return;
		undoStack.push(snapshot());
		if (undoStack.size() > MAX_UNDO_DEPTH) undoStack.pollLast();
		redoStack.clear();
	}

	private void applySnapshot(GridSnapshot snap) {
		if (projectData == null) return;
		Map<String, Map<Point, ControlEvent>> liveEvent = projectData.getEventDataByCanvas();
		liveEvent.clear();
		for (Map.Entry<String, Map<Point, ControlEvent>> e : snap.eventDataByCanvas.entrySet())
			liveEvent.put(e.getKey(), new HashMap<>(e.getValue()));
		Map<InstrumentDataKey, NoteCell> liveInstr = projectData.getInstrumentData();
		liveInstr.clear();
		liveInstr.putAll(snap.instrumentData);
		fileHasBeenModified.set(true);
		updateWindowTitle();
		updateTupletCache();
		updateMeasureLinePositions();
		mainInterfacePanel.repaint();
	}

	void enterInlineMode(InlineEntryPanel panel) {
		activeInlinePanel = panel;
		swallowNextTyped = true;
		mainInterfacePanel.repaint();
	}
	void exitInlineMode() {
		activeInlinePanel = null;
		mainInterfacePanel.repaint();
	}

	public void updateTupletCache() {
		TreeMap<Integer, TupletEvent> result = new TreeMap<>();
		if (projectData != null) {
			for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
				if (!(c instanceof EventCanvasConfig)) continue;
				for (Map.Entry<Point, ControlEvent> e : projectData.getEventData(c.getId()).entrySet()) {
					if (e.getValue().getType() == ControlEventType.TUPLET) {
						result.put(e.getKey().x, (TupletEvent) e.getValue());
					}
				}
			}
		}
		cachedTuplets.clear();
		cachedTuplets.putAll(result);
	}

	// Storage range [t0, t0+B) — used by rendering and cursor guards
	TupletEvent getTupletAt(int t) {
		Integer start = cachedTuplets.floorKey(t);
		if (start == null) return null;
		TupletEvent te = cachedTuplets.get(start);
		return (t < start + te.getSubdivisions()) ? te : null;
	}

	// Clock range [t0, t0+max(A,B)) — used by playback scheduler.
	// When B > A, storage cells [t0+A, t0+B) also fire at tuplet timing.
	TupletEvent getTupletAtClock(int t) {
		Integer start = cachedTuplets.floorKey(t);
		if (start == null) return null;
		TupletEvent te = cachedTuplets.get(start);
		int A = te.getEndT() - start;
		int B = te.getSubdivisions();
		return (t < start + Math.max(A, B)) ? te : null;
	}

	boolean isInsideTuplet(int t) { return getTupletAt(t) != null; }

	static String tupletLabel(TupletEvent te, int t0) {
		int subdivs = te.getSubdivisions();
		int ticks   = te.getEndT() - t0;
		int g = subdivs; int r = ticks; while (r != 0) { int tmp = r; r = g % r; g = tmp; }
		String base = subdivs + ":" + ticks;
		return (g > 1) ? base + " (" + (subdivs / g) + ":" + (ticks / g) + ")" : base;
	}


	void rebuildGridFont() {
		gridFont = new Font(gridFontFamily, Font.PLAIN, gridFontSize);
		italicGridFont = gridFont.deriveFont(Font.ITALIC);
		gridFontMetrics = new Canvas().getFontMetrics(gridFont);
		updateMeasureLinePositions();
		if (mainInterfacePanel != null) mainInterfacePanel.repaint();
	}

	void rebuildTextFont() {
		textFont = new Font(textFontFamily, Font.PLAIN, textFontSize);
		textFontMetrics = new Canvas().getFontMetrics(textFont);
		if (mainInterfacePanel != null) mainInterfacePanel.repaint();
	}

	public static TabbyCat getInstance() {
		if (instance == null) {
			instance = new TabbyCat();
		}
		return instance;
	}

	private TabbyCat() {
		scanSf2Dir(new File("sf2"));
		Collections.sort(sf2Files);
		createGui();
	}

	private void scanSf2Dir(File dir) {
		File[] entries = dir.listFiles();
		if (entries == null) return;
		for (File f : entries) {
			if (f.isDirectory()) scanSf2Dir(f);
			else if (f.getName().toLowerCase().endsWith(".sf2")) sf2Files.add(f);
		}
	}
	
	void createGui() {
		
		cardLayout = new CardLayout();
		cardPanel = new JPanel(cardLayout);
		
		loadProjectPanel = new LoadProjectPanel();
		cardPanel.add(loadProjectPanel,loadProjectCardKey);
		mainInterfacePanel = new MainInterfacePanel();
		cardPanel.add(mainInterfacePanel,mainInterfaceCardKey);
		saveProjectPanel = new SaveProjectPanel();
		cardPanel.add(saveProjectPanel,saveProjectCardKey);
		timeSignatureEventPanel = new TimeSignatureEventPanel();
		tempoEventPanel = new TempoEventPanel();
		shuffleEventPanel = new ShuffleEventPanel();
		notesEventPanel = new TextInputPanel(
			"Add note:",
			text -> {
				Pair<Integer,Integer> notePair = mainInterfacePanel.getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
				CanvasConfig noteCanvas = projectData.getCanvases().getCanvases().get(notePair.a);
				if (noteCanvas instanceof EventCanvasConfig) {
					pushUndo();
					projectData.getEventData(noteCanvas.getId()).put(
						new Point(projectData.getCursorT().get(), notePair.b),
						new StickyNote(text));
				}
				exitInlineMode();
			},
			() -> exitInlineMode()
		);
		programChangeEventPanel = new ProgramChangeEventPanel();
		tuningChangeEventPanel = new TuningChangeEventPanel();
		tupletEventPanel = new TupletEventPanel();
		helpPanel = new HelpPanel();
		cardPanel.add(helpPanel,helpCardKey);
		settingsPanel = new SettingsPanel();
		cardPanel.add(settingsPanel,settingsCardKey);
		audioOutputPanel = new AudioOutputPanel();
		cardPanel.add(audioOutputPanel,audioOutputCardKey);
		uiSettingsPanel = new UISettingsPanel();
		cardPanel.add(uiSettingsPanel, uiSettingsCardKey);
		instrumentSettingsPanel = new InstrumentSettingsPanel();
		cardPanel.add(instrumentSettingsPanel,instrumentSettingsCardKey);
		stringEditorPanel = new StringEditorPanel();
		cardPanel.add(stringEditorPanel, stringEditorCardKey);
		additionalFretEditorPanel = new AdditionalFretEditorPanel();
		cardPanel.add(additionalFretEditorPanel, additionalFretEditorCardKey);
		drumEditorPanel = new DrumEditorPanel();
		cardPanel.add(drumEditorPanel, drumEditorCardKey);
		eventCanvasSettingsPanel = new EventCanvasSettingsPanel();
		cardPanel.add(eventCanvasSettingsPanel, eventCanvasSettingsCardKey);
		deleteConfirmPanel = new DeleteConfirmPanel();
		cardPanel.add(deleteConfirmPanel, deleteConfirmCardKey);
		newCanvasPanel = new NewCanvasPanel();
		cardPanel.add(newCanvasPanel, newCanvasCardKey);
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
			List<CanvasConfig> canvases = new java.util.ArrayList<>();
			canvases.add(new EventCanvasConfig("Events", 3));
			projectData = new ProjectFileData(new CanvasesConfig(canvases));
			projectData.setSongName("New Song");
			projectData.setArtistName("Artist");
			projectData.setUiScale(displayScale);
			activeFile.set(null);
			undoStack.clear(); redoStack.clear();
			warmupSynths();
			cardLayout.show(cardPanel, mainInterfaceCardKey);
		}

		frame.setVisible(true);
		try {
			byte[] iconBytes = java.util.Base64.getDecoder().decode(
				"iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAAXNSR0IArs4c6QAAAERlWElm" +
				"TU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAEKAD" +
				"AAQAAAABAAAAEAAAAAA0VXHyAAABDUlEQVQ4EaVTKw7CQBTcEiwVGBCQIugVuERJ0GgE" +
				"gjtwCwQCjSah4Q5coQaCwQBJL1B2tp3Na7uLYZL2/WZmXzcQFBrqD3SkNhxOZOnNJa9L" +
				"Fpr5+q5MfN5M5Iwxr/qSZw0siSY6NsFDan3cAdEbREWxVaZUl0/ZdtSGV4kCRDimaWqM" +
				"k+u8doCrSGfnkpskyhqggxWPh105hFF8Mrl5ZQtF4XK1UbgPoGWAC6oBJlosEe4jtwFI" +
				"vz6FGyR6dcJuYIV66LxtreDJkmsN6MgIkyb43bIfvEbT1k+5/8gs5z2OVbO2Q510MJQEm" +
				"VOMKCE5tf+CJDFvitFnD0ZeA54OEh6KYMAN0PMagOiDNPsCUK22r7blkgMAAAAASUVORK5CYII=");
			BufferedImage iconSmall = ImageIO.read(new java.io.ByteArrayInputStream(iconBytes));
			int iconSize = 512;
			BufferedImage iconImage = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB);
			Graphics2D ig = iconImage.createGraphics();
			ig.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			ig.drawImage(iconSmall, 0, 0, iconSize, iconSize, null);
			ig.dispose();
			frame.setIconImage(iconImage);
			if (IS_MAC) {
				// Try java.awt.Taskbar (Java 9+) first, fall back to com.apple.eawt.Application
				boolean taskbarSet = false;
				try {
					Class<?> taskbarClass = Class.forName("java.awt.Taskbar");
					Object taskbar = taskbarClass.getMethod("getTaskbar").invoke(null);
					taskbarClass.getMethod("setIconImage", java.awt.Image.class).invoke(taskbar, iconImage);
					taskbarSet = true;
				} catch (Exception ignored) {}
				if (!taskbarSet) {
					try {
						Class<?> appClass = Class.forName("com.apple.eawt.Application");
						Object app = appClass.getMethod("getApplication").invoke(null);
						appClass.getMethod("setDockIconImage", java.awt.Image.class).invoke(app, iconImage);
					} catch (Exception ignored) {}
				}
			}
		} catch (Exception ignored) {}
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
		}
		if (projectFileData.getRainbowBrightness() > 0) {
			rainbowBrightness = projectFileData.getRainbowBrightness();
			rbColors = buildRbColors();
		}
		if (projectFileData.getScrollbarBrightness() > 0) {
			scrollbarBrightness = projectFileData.getScrollbarBrightness();
			scrollbarRainbowImage = buildScrollbarRainbow();
		}
		if (projectFileData.getGridFontFamily() != null) {
			gridFontFamily = projectFileData.getGridFontFamily();
		}
		if (projectFileData.getGridFontSize() > 0) {
			gridFontSize = projectFileData.getGridFontSize();
		}
		rebuildGridFont();
		if (projectFileData.getTextFontFamily() != null) {
			textFontFamily = projectFileData.getTextFontFamily();
		}
		if (projectFileData.getTextFontSize() > 0) {
			textFontSize = projectFileData.getTextFontSize();
		}
		rebuildTextFont();
		settingsPanel.refresh();
		updateTupletCache();
		updateMeasureLinePositions();
		for (CanvasConfig canvasConfig : projectData.getCanvases().getCanvases()) {
			if (canvasConfig instanceof StringCanvasConfig) {
				StringCanvasConfig a = (StringCanvasConfig) canvasConfig;
				for (int row = 0; row < a.getRowCount(); row++) {
					getSynth(a, row);
				}
			} else if (canvasConfig instanceof DrumCanvasConfig) {
				getSynth((DrumCanvasConfig) canvasConfig);
			}
			// EventCanvasConfig has no synth
		}
		undoStack.clear(); redoStack.clear();
		warmupSynths();
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
	
	class IntSpinner {
		private final String label;
		private final int defaultValue;
		private final int min, max;
		private final int[] allowedValues;
		private int value;

		IntSpinner(String label, int defaultValue, int min, int max) {
			this.label = label; this.defaultValue = defaultValue;
			this.min = min; this.max = max; this.allowedValues = null;
			this.value = defaultValue;
		}
		IntSpinner(String label, int defaultValue, int[] allowedValues) {
			this.label = label; this.defaultValue = defaultValue;
			this.min = 0; this.max = 0; this.allowedValues = allowedValues;
			this.value = defaultValue;
		}
		void up() {
			if (allowedValues != null) {
				for (int i = 0; i < allowedValues.length; i++)
					if (allowedValues[i] == value && i + 1 < allowedValues.length) { value = allowedValues[i+1]; return; }
			} else { value = Math.min(max, value + 1); }
		}
		void down() {
			if (allowedValues != null) {
				for (int i = allowedValues.length - 1; i >= 0; i--)
					if (allowedValues[i] == value && i - 1 >= 0) { value = allowedValues[i-1]; return; }
			} else { value = Math.max(min, value - 1); }
		}
		void left()  { down(); }
		void right() { up(); }
		void reset() { value = defaultValue; }
		void setValue(int v) { value = (allowedValues == null) ? Math.max(min, Math.min(max, v)) : v; }
		int getValue() { return value; }
		int paint(Graphics2D g, int x, int y, boolean focused) {
			g.setFont(textFont);
			String labelStr = label + ":";
			int labelW = textFontMetrics.stringWidth(labelStr);
			int lineH  = textFontMetrics.getMaxAscent();
			String maxStr = (allowedValues != null)
				? String.valueOf(allowedValues[allowedValues.length - 1])
				: String.valueOf(max);
			int boxW = textFontMetrics.stringWidth(maxStr) + 8;
			g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString(labelStr, x, y);
			g.setPaint(focused ? Color.GRAY : new Color(50, 50, 50));
			g.fillRect(x + labelW + 4, y - lineH, boxW, lineH);
			g.setPaint(new Color(255, 255, 100));
			g.drawString(value + "", x + labelW + 6, y);
			return x + labelW + 4 + boxW;
		}
	}

	class FloatSpinner {
		private final String label;
		private final double min, max, step;
		private double value;

		FloatSpinner(String label, double initialValue, double min, double max, double step) {
			this.label = label; this.value = initialValue;
			this.min = min; this.max = max; this.step = step;
		}
		void right() { value = Math.min(max, Math.round((value + step) * 10.0) / 10.0); }
		void left()  { value = Math.max(min, Math.round((value - step) * 10.0) / 10.0); }
		double getValue() { return value; }
		void setValue(double v) { value = v; }
		void paint(Graphics2D g, int x, int y, boolean focused) {
			g.setFont(textFont);
			String labelStr = label + ":";
			int labelW = textFontMetrics.stringWidth(labelStr);
			int lineH  = textFontMetrics.getMaxAscent();
			int boxW   = textFontMetrics.stringWidth("00.0") + 8;
			g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString(labelStr, x, y);
			g.setPaint(focused ? Color.GRAY : new Color(50, 50, 50));
			g.fillRect(x + labelW + 4, y - lineH, boxW, lineH);
			g.setPaint(focused ? new Color(255, 255, 100) : Color.GRAY);
			g.drawString(String.format("%.1f", value), x + labelW + 6, y);
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
	

	private static class GridSnapshot {
		final Map<String, Map<Point, ControlEvent>> eventDataByCanvas;
		final Map<InstrumentDataKey, NoteCell> instrumentData;
		GridSnapshot(Map<String, Map<Point, ControlEvent>> eventSrc, Map<InstrumentDataKey, NoteCell> instrSrc) {
			Map<String, Map<Point, ControlEvent>> evCopy = new HashMap<>();
			for (Map.Entry<String, Map<Point, ControlEvent>> e : eventSrc.entrySet())
				evCopy.put(e.getKey(), new HashMap<>(e.getValue()));
			this.eventDataByCanvas = evCopy;
			this.instrumentData = new HashMap<>(instrSrc);
		}
	}

	interface InlineEntryPanel {
		void inlineUp();
		void inlineDown();
		void inlineLeft();
		void inlineRight();
		void inlineEnter();
		void paintInline(Graphics2D g, int x, int y, int w, int h);
	}

	enum SequencePosition {
		NEW, OPEN, SAVE, SAVE_AS, UNDO, REDO, TEMPO, SHUFFLE, TAPPER, SETTINGS, HELP, FOLLOW, SET_REPEAT_START, SET_REPEAT_END;
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
		
		final Map<InstrumentDataKey, NoteCell> instrumentClipboard = new HashMap<>();
		final Map<Point, ControlEvent> eventClipboard = new HashMap<>();
		
		int lassoCanvasNumber = -1;
		int lassoT0 = -1;
		int lassoRow0 = -1;
		String modePartialBuffer = null;
		InstrumentDataKey modePartialKey = null;
		boolean isMouseLasso = false;

		boolean isTupletSelectionMode = false;
		int tupletSelectionT0 = -1;
		String tupletSelectionCanvasId = null;
		int tupletSelectionRow = -1;
		int pendingTupletT0 = -1;
		int pendingTupletT1 = -1;

		double horizScrollAccum = 0.0;
		double vertScrollAccum  = 0.0;
		int viewY = 0;

		List<Shape> lastCanvasGrids = new ArrayList<>();
		int lastVerticalTranslate = 0;
		int lastCellWidth = 1;
		int lastRowHeight = 1;
		int lastT1 = 1;
		// Track strip click bounds: [canvasIndex][0=up,1=down,2=delete]
		List<Rectangle2D[]> lastStripBounds = new ArrayList<>();
		Rectangle2D lastAddCanvasBounds = null;
		Map<SequencePosition, Rectangle2D> menuItemBounds = new EnumMap<>(SequencePosition.class);
		int lastTopBarHeight = 0;
		boolean followPlayback = true;
		Rectangle2D lastScrollbarBounds = null;
		Rectangle2D lastScrollbarThumbBounds = null;
		boolean isScrollbarDragging = false;
		double scrollbarDragStartX = 0;
		int scrollbarDragStartViewT = 0;
		
		public MainInterfacePanel() {
			this.setFocusTraversalKeysEnabled(false);
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();

			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(() -> { if (activeInlinePanel != null) { activeInlinePanel.inlineUp(); repaint(); return; } up(); }));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(() -> { if (activeInlinePanel != null) { activeInlinePanel.inlineDown(); repaint(); return; } down(); }));
			inputMap.put(k_Left,"left");
			actionMap.put("left", rToA(() -> { if (activeInlinePanel != null) { activeInlinePanel.inlineLeft(); repaint(); return; } left(); }));
			inputMap.put(k_Right,"right");
			actionMap.put("right", rToA(() -> { if (activeInlinePanel != null) { activeInlinePanel.inlineRight(); repaint(); return; } right(); }));
			
			inputMap.put(k_ShiftUp,"shiftup");
			actionMap.put("shiftup", rToA(this::shiftUp));
			inputMap.put(k_ShiftDown,"shiftdown");
			actionMap.put("shiftdown", rToA(this::shiftDown));
			inputMap.put(k_AltUp,   "altup");
			actionMap.put("altup",   rToA(this::drumNoteUp));
			inputMap.put(k_AltDown, "altdown");
			actionMap.put("altdown", rToA(this::drumNoteDown));
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
			actionMap.put("escape", rToA(() -> { if (activeInlinePanel != null) { exitInlineMode(); return; } cancelSelectionMode(); }));
			inputMap.put(k_CtrlL,"ctrll");
			actionMap.put("ctrll", rToA(this::ctrlL));
			inputMap.put(k_CtrlC,"ctrlc");
			actionMap.put("ctrlc", rToA(this::ctrlC));
			inputMap.put(k_CtrlX,"ctrlx");
			actionMap.put("ctrlx", rToA(this::ctrlX));
			inputMap.put(k_CtrlV,"ctrlv");
			actionMap.put("ctrlv", rToA(this::ctrlV));
			inputMap.put(k_CmdBracketLeft,"cmdbracketleft");
			actionMap.put("cmdbracketleft", rToA(this::cmdBracketLeft));
			inputMap.put(k_CmdBracketRight,"cmdbracketright");
			actionMap.put("cmdbracketright", rToA(this::cmdBracketRight));
			inputMap.put(k_CtrlS,"ctrls");
			actionMap.put("ctrls", rToA(this::ctrlS));
			inputMap.put(k_CtrlShiftS,"ctrlshifts");
			actionMap.put("ctrlshifts", rToA(this::showSaveAs));
			inputMap.put(k_CtrlE,"ctrle");
			actionMap.put("ctrle", rToA(this::ctrlI));
			inputMap.put(k_CtrlZ, "ctrlz");
			actionMap.put("ctrlz", rToA(this::ctrlZ));
			inputMap.put(k_CtrlR, "ctrlr");
			actionMap.put("ctrlr", rToA(this::ctrlR));

			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(() -> { if (activeInlinePanel != null) { activeInlinePanel.inlineEnter(); return; } enter(); }));

			inputMap.put(k_Comma,"comma");
			actionMap.put("comma", rToA(this::comma));

			inputMap.put(k_Space,"space");
			actionMap.put("space", rToA(() -> { if (activeInlinePanel instanceof TextInputPanel) return; TabbyCat.this.togglePlayStatus(); }));
			inputMap.put(k_ShiftSpace,"shiftspace");
			actionMap.put("shiftspace", rToA(TabbyCat.this::playFromStart));
			
			inputMap.put(k_Backspace,"backspace");
			actionMap.put("backspace", rToA(this::backspace));
			
			inputMap.put(k_Hyphen,"hyphen");
			actionMap.put("hyphen", rToA(this::hyphen));

			inputMap.put(k_Delete,       "delete");
			actionMap.put("delete",      rToA(this::delete));
			inputMap.put(k_Home,         "home");
			actionMap.put("home",        rToA(this::home));
			inputMap.put(k_End,          "end");
			actionMap.put("end",         rToA(this::end));
			inputMap.put(k_PageUp,       "pageup");
			actionMap.put("pageup",      rToA(this::pageUp));
			inputMap.put(k_PageDown,     "pagedown");
			actionMap.put("pagedown",    rToA(this::pageDown));
			inputMap.put(k_CtrlI, "addtick");
			actionMap.put("addtick", rToA(this::addTick));
			inputMap.put(k_CtrlD, "deltick");
			actionMap.put("deltick", rToA(this::deleteTick));
			
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
			inputMap.put(k_ModeNote, "modenote");
			actionMap.put("modenote", rToA(()->handleCharInput('n')));
			inputMap.put(k_ModeVel, "modevel");
			actionMap.put("modevel", rToA(()->handleCharInput('v')));
			inputMap.put(k_ModePan, "modepan");
			actionMap.put("modepan", rToA(()->handleCharInput('p')));
			inputMap.put(k_ModeOffset, "modeoffset");
			actionMap.put("modeoffset", rToA(()->handleCharInput('o')));

			// Route all printable KEY_TYPED characters to TextInputPanel when active.
			// This handles lowercase, uppercase (shift+letter), and arbitrary punctuation
			// that the InputMap's KEY_PRESSED bindings can't deliver with correct case.
			java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
				if (e.getID() == java.awt.event.KeyEvent.KEY_TYPED) {
					char c = e.getKeyChar();
					if (c != java.awt.event.KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
						if (activeInlinePanel instanceof TextInputPanel) {
							if (swallowNextTyped) { swallowNextTyped = false; return true; }
							((TextInputPanel) activeInlinePanel).appendInline(c);
							repaint();
							return true;
						}
						if (eventCanvasSettingsPanel != null && eventCanvasSettingsPanel.isShowing()) {
							eventCanvasSettingsPanel.appendToName(c);
							return true;
						}
					}
				}
				return false;
			});

			this.addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (SwingUtilities.isRightMouseButton(e) && (!instrumentClipboard.isEmpty() || !eventClipboard.isEmpty())) {
						requestFocusInWindow();
						ctrlV();
						return;
					}
					double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
					double scaledX = e.getX() * deviceScale / displayScale;
					double scaledY = e.getY() * deviceScale / displayScale;
					if (scaledY < lastTopBarHeight) {
						for (Map.Entry<SequencePosition, Rectangle2D> entry : menuItemBounds.entrySet()) {
							if (entry.getValue().contains(scaledX, scaledY)) {
								SequencePosition clicked = entry.getKey();
								if (clicked == SequencePosition.TEMPO || clicked == SequencePosition.SHUFFLE) {
									requestFocusInWindow();
									sequencePosition = clicked;
									isInGrid = false;
									enter();
								} else {
									SequencePosition savedPos = sequencePosition;
									boolean savedIsInGrid = isInGrid;
									sequencePosition = clicked;
									isInGrid = false;
									enter();
									sequencePosition = savedPos;
									isInGrid = savedIsInGrid;
									repaint();
								}
								return;
							}
						}
						return;
					}
					requestFocusInWindow();
					if (lastScrollbarBounds != null && lastScrollbarBounds.contains(scaledX, scaledY)) {
						isScrollbarDragging = true;
						scrollbarDragStartX = scaledX;
						scrollbarDragStartViewT = projectData.getViewT().get();
						if (lastScrollbarThumbBounds == null || !lastScrollbarThumbBounds.contains(scaledX, scaledY)) {
							double W_ = lastScrollbarBounds.getWidth();
							int maxET = lastT1;
							for (CanvasConfig c_ : projectData.getCanvases().getCanvases()) {
								if (c_ instanceof EventCanvasConfig) {
									for (Point p : projectData.getEventData(c_.getId()).keySet()) maxET = Math.max(maxET, p.x + 1);
								}
							}
							for (InstrumentDataKey k : projectData.getInstrumentData().keySet()) maxET = Math.max(maxET, k.getTime() + 1);
							if (maxET > 0) {
								int newViewT = (int)(scaledX / W_ * maxET);
								int tDelta_ = (int)(W_ / lastCellWidth);
								projectData.getViewT().set(Math.max(0, newViewT - tDelta_ / 2));
								scrollbarDragStartViewT = projectData.getViewT().get();
								scrollbarDragStartX = scaledX;
							}
						}
						repaint();
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

					// Check + ADD CANVAS bar
					if (lastAddCanvasBounds != null && lastAddCanvasBounds.contains(mx, my)) {
						newCanvasPanel.prepare();
						cardLayout.show(cardPanel, newCanvasCardKey);
						repaint();
						return;
					}
					// Check track strip buttons
					for (int si = 0; si < lastStripBounds.size(); si++) {
						Rectangle2D[] sb = lastStripBounds.get(si);
						if (sb == null) continue;
						if (sb[0].contains(mx, my) && si > 0) {
							// Move up
							projectData.getCanvases().swapCanvases(si, si - 1);
							repaint();
							return;
						}
						if (sb[1].contains(mx, my) && si < lastStripBounds.size() - 1) {
							// Move down
							projectData.getCanvases().swapCanvases(si, si + 1);
							repaint();
							return;
						}
						// bounds order: [0]=up [1]=down [2]=edit [3]=mute [4]=delete
						if (sb.length > 2 && sb[2] != null && sb[2].contains(mx, my)) {
							// Edit — open settings for this canvas
							CanvasConfig canvas = projectData.getCanvases().getCanvases().get(si);
							if (canvas instanceof DrumCanvasConfig) {
								drumEditorPanel.prepare((DrumCanvasConfig) canvas);
								cardLayout.show(cardPanel, drumEditorCardKey);
							} else if (canvas instanceof StringCanvasConfig) {
								instrumentSettingsPanel.prepare((StringCanvasConfig) canvas);
								cardLayout.show(cardPanel, instrumentSettingsCardKey);
							} else if (canvas instanceof EventCanvasConfig) {
								eventCanvasSettingsPanel.prepare((EventCanvasConfig) canvas);
								cardLayout.show(cardPanel, eventCanvasSettingsCardKey);
							}
							repaint();
							return;
						}
						if (sb.length > 3 && sb[3] != null && sb[3].contains(mx, my)) {
							// Mute toggle
							CanvasConfig canvas = projectData.getCanvases().getCanvases().get(si);
							canvas.setMuted(!canvas.isMuted());
							if (!fileHasBeenModified.get()) { fileHasBeenModified.set(true); updateWindowTitle(); }
							repaint();
							return;
						}
						if (sb.length > 4 && sb[4] != null && sb[4].contains(mx, my)) {
							// Delete
							CanvasConfig dcCanvas = projectData.getCanvases().getCanvases().get(si);
							deleteConfirmPanel.prepare(si, dcCanvas.getName(), dcCanvas.getId());
							cardLayout.show(cardPanel, deleteConfirmCardKey);
							repaint();
							return;
						}
						if (sb.length > 5 && sb[5] != null && sb[5].contains(mx, my)) {
							CanvasConfig modeCanvas = projectData.getCanvases().getCanvases().get(si);
							modeCanvas.setEditMode(CanvasEditMode.NOTE);
							modePartialBuffer = null; modePartialKey = null;
							repaint(); return;
						}
						if (sb.length > 6 && sb[6] != null && sb[6].contains(mx, my)) {
							CanvasConfig modeCanvas = projectData.getCanvases().getCanvases().get(si);
							modeCanvas.setEditMode(CanvasEditMode.VELOCITY);
							modePartialBuffer = null; modePartialKey = null;
							repaint(); return;
						}
						if (sb.length > 7 && sb[7] != null && sb[7].contains(mx, my)) {
							CanvasConfig modeCanvas = projectData.getCanvases().getCanvases().get(si);
							modeCanvas.setEditMode(CanvasEditMode.PAN);
							modePartialBuffer = null; modePartialKey = null;
							repaint(); return;
						}
						if (sb.length > 8 && sb[8] != null && sb[8].contains(mx, my)) {
							CanvasConfig modeCanvas = projectData.getCanvases().getCanvases().get(si);
							modeCanvas.setEditMode(CanvasEditMode.OFFSET);
							modePartialBuffer = null; modePartialKey = null;
							repaint(); return;
						}
					}

					for (int i = 0; i < lastCanvasGrids.size(); i++) {
						Rectangle2D bounds = lastCanvasGrids.get(i).getBounds2D();
						if (bounds.contains(mx, my)) {
							int clickedT = projectData.getViewT().get()
									+ (int) ((mx - bounds.getMinX()) / lastCellWidth);
							int clickedRelativeRow = (int) ((my - bounds.getMinY()) / lastRowHeight);

							int maxRelRow = projectData.getCanvases().getCanvases().get(i).getRowCount();
							if (clickedRelativeRow < 0 || clickedRelativeRow >= maxRelRow) break;

							int absoluteRow = (i == 0)
									? clickedRelativeRow
									: rowBreaks.get(i - 1) + 1 + clickedRelativeRow;

							if (SwingUtilities.isRightMouseButton(e)) {
								projectData.getPlaybackT0().set(Math.max(0, clickedT));
							} else if (e.isShiftDown()) {
								projectData.getCursorT().set(Math.max(0, clickedT));
								projectData.getSelectedRow().set(absoluteRow);
								isInGrid = true;
								if (!isSelectionMode.get()) {
									isMouseLasso = true;
									toggleSelectionMode();
								}
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
					if (isScrollbarDragging) {
						isScrollbarDragging = false;
						repaint();
						return;
					}
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
					if (isScrollbarDragging && lastScrollbarBounds != null) {
						double W_ = lastScrollbarBounds.getWidth();
						int maxET = (int)(W_ / lastCellWidth) + scrollbarDragStartViewT;
						for (CanvasConfig c_ : projectData.getCanvases().getCanvases()) {
							if (c_ instanceof EventCanvasConfig) {
								for (Point p : projectData.getEventData(c_.getId()).keySet()) maxET = Math.max(maxET, p.x + 1);
							}
						}
						for (InstrumentDataKey k : projectData.getInstrumentData().keySet()) maxET = Math.max(maxET, k.getTime() + 1);
						if (maxET > 0) {
							double dX = scaledX - scrollbarDragStartX;
							int dT = (int)(dX / W_ * maxET);
							projectData.getViewT().set(Math.max(0, scrollbarDragStartViewT + dT));
						}
						repaint();
						return;
					}
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
			if (activeInlinePanel instanceof TextInputPanel) return;
			if (isInGrid) {
				Pair<Integer,Integer> pair = 
						getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
				int canvasNum = pair.a;
				int relativeRow = pair.b;
				CanvasConfig canvasConfig = projectData.getCanvases().getCanvases().get(canvasNum);
				if (canvasConfig instanceof EventCanvasConfig) {
					return;
				}
				if (canvasConfig instanceof DrumCanvasConfig) {
					return;
				}
				pushUndo();
				for (int t = projectData.getCursorT().get()+1;
						projectData.getInstrumentData().get(new InstrumentDataKey(canvasConfig.getId(),t,relativeRow)) != null &&
						projectData.getInstrumentData().get(new InstrumentDataKey(canvasConfig.getId(),t,relativeRow)).token.charAt(0) == '-' ;
						t++) {
					InstrumentDataKey dk = new InstrumentDataKey(canvasConfig.getId(),t,relativeRow);
					projectData.getInstrumentData().remove(dk);
				}
				int cursorT = projectData.getCursorT().get();
				for (int t = cursorT;
						t >= cursorT - 16 &&
						projectData.getInstrumentData().get(new InstrumentDataKey(canvasConfig.getId(),t,relativeRow)) == null;
						t--) {
					InstrumentDataKey dk = new InstrumentDataKey(canvasConfig.getId(),t,relativeRow);
					projectData.getInstrumentData().put(dk, NoteCell.defaultOf("-"));
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

		void newProject() {
			if (fileHasBeenModified.get()) {
				int choice = javax.swing.JOptionPane.showConfirmDialog(
					frame,
					"You have unsaved changes. Save before creating a new project?",
					"Unsaved Changes",
					javax.swing.JOptionPane.YES_NO_CANCEL_OPTION,
					javax.swing.JOptionPane.WARNING_MESSAGE);
				if (choice == javax.swing.JOptionPane.CANCEL_OPTION || choice == javax.swing.JOptionPane.CLOSED_OPTION) return;
				if (choice == javax.swing.JOptionPane.YES_OPTION) ctrlS();
			}
			CanvasesConfig config = new CanvasesConfig(java.util.Arrays.asList(new EventCanvasConfig("Events", 3)));
			projectData = new ProjectFileData(config);
			projectData.setUiScale(displayScale);
			activeFile.set(null);
			fileHasBeenModified.set(false);
			updateWindowTitle();
			undoStack.clear(); redoStack.clear();
			warmupSynths();
			updateTupletCache();
			updateMeasureLinePositions();
			isInGrid = false;
			cardLayout.show(cardPanel, mainInterfaceCardKey);
			repaint();
		}

		void ctrlI() {
			if (projectData == null) return;
			Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNum = p.a;
			if (canvasNum < 0 || canvasNum >= projectData.getCanvases().getCanvases().size()) return;
			CanvasConfig canvas = projectData.getCanvases().getCanvases().get(canvasNum);
			if (canvas instanceof DrumCanvasConfig) {
				drumEditorPanel.prepare((DrumCanvasConfig) canvas);
				cardLayout.show(cardPanel, drumEditorCardKey);
			} else if (canvas instanceof StringCanvasConfig) {
				instrumentSettingsPanel.prepare((StringCanvasConfig) canvas);
				cardLayout.show(cardPanel, instrumentSettingsCardKey);
			} else if (canvas instanceof EventCanvasConfig) {
				eventCanvasSettingsPanel.prepare((EventCanvasConfig) canvas);
				cardLayout.show(cardPanel, eventCanvasSettingsCardKey);
			}
		}


		void backspace() {
			if (activeInlinePanel instanceof TextInputPanel) {
				((TextInputPanel) activeInlinePanel).backspaceInline();
				repaint();
				return;
			}
			if (!instrumentClipboard.isEmpty() || !eventClipboard.isEmpty()) {
				ctrlC();
				return;
			}
			pushUndo();
			if (!fileHasBeenModified.get()) {
				fileHasBeenModified.set(true);
				updateWindowTitle();
			}
			Pair<Integer,Integer> pair =
					getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNumber = pair.a;
			int row = pair.b;
			CanvasConfig bsCanvas = projectData.getCanvases().getCanvases().get(canvasNumber);
			if (bsCanvas instanceof EventCanvasConfig) {
				projectData.getEventData(bsCanvas.getId()).remove(
						new Point(projectData.getCursorT().get(), row));
			} else if (bsCanvas instanceof DrumCanvasConfig) {
				InstrumentDataKey dataKey = new InstrumentDataKey(bsCanvas.getId(), projectData.getCursorT().get(), row);
				NoteCell cur = projectData.getInstrumentData().get(dataKey);
				if (cur != null && cur.token.length() > 1)
					projectData.getInstrumentData().put(dataKey, cur.withToken(cur.token.substring(0, cur.token.length() - 1)));
				else
					projectData.getInstrumentData().remove(dataKey);
			} else {
				InstrumentDataKey dataKey =
						new InstrumentDataKey(bsCanvas.getId(),
								projectData.getCursorT().get(), row);
				projectData.getInstrumentData().remove(dataKey);
			}
			if (!fileHasBeenModified.get()) {
				fileHasBeenModified.set(true);
				updateWindowTitle();
			}
			updateTupletCache();
			updateMeasureLinePositions();
			repaint();
		}

		void delete() {
			if (!isInGrid) return;
			pushUndo();
			Pair<Integer,Integer> pair = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNumber = pair.a;
			int row = pair.b;
			CanvasConfig delCanvas = projectData.getCanvases().getCanvases().get(canvasNumber);
			if (delCanvas instanceof EventCanvasConfig) {
				projectData.getEventData(delCanvas.getId()).remove(new Point(projectData.getCursorT().get(), row));
			} else {
				projectData.getInstrumentData().remove(new InstrumentDataKey(delCanvas.getId(), projectData.getCursorT().get(), row));
			}
			if (!fileHasBeenModified.get()) {
				fileHasBeenModified.set(true);
				updateWindowTitle();
			}
			updateTupletCache();
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
			if (activeInlinePanel instanceof TextInputPanel) return;
			if (isTupletSelectionMode) return;
			if (!fileHasBeenModified.get()) {
				fileHasBeenModified.set(true);
				updateWindowTitle();
			}
			
			Pair<Integer,Integer> pair = 
					getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNumber = pair.a;
			int row = pair.b;
			CanvasConfig charCanvas = projectData.getCanvases().getCanvases().get(canvasNumber);
			if (charCanvas instanceof EventCanvasConfig) {
				//event canvas
				if (c == 'T') {
					enterInlineMode(timeSignatureEventPanel);
				} else if (c == 'S') {
					enterInlineMode(tempoEventPanel);
				} else if (c == 'F') {
					enterInlineMode(shuffleEventPanel);
				} else if (c == 'N') {
					notesEventPanel.reset();
					enterInlineMode(notesEventPanel);
				} else if (c == 'P') {
					programChangeEventPanel.refresh();
					enterInlineMode(programChangeEventPanel);
				} else if (c == 'U') {
					tuningChangeEventPanel.refresh();
					enterInlineMode(tuningChangeEventPanel);
				} else if (c == 'Q') {
					if (isInGrid && !isSelectionMode.get()) {
						if (isInsideTuplet(projectData.getCursorT().get())) return;
						Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
						isTupletSelectionMode = true;
						tupletSelectionT0 = projectData.getCursorT().get();
						tupletSelectionCanvasId = projectData.getCanvases().getCanvases().get(p.a).getId();
						tupletSelectionRow = p.b;
						repaint();
					}
				}
			} else {
				CanvasConfig canvasConfig = charCanvas;
				// Mode switch shortcuts
				if (c == 'n') { canvasConfig.setEditMode(CanvasEditMode.NOTE); modePartialBuffer = null; modePartialKey = null; repaint(); return; }
				if (c == 'v') { canvasConfig.setEditMode(CanvasEditMode.VELOCITY); modePartialBuffer = null; modePartialKey = null; repaint(); return; }
				if (c == 'p') { canvasConfig.setEditMode(CanvasEditMode.PAN); modePartialBuffer = null; modePartialKey = null; repaint(); return; }
				if (c == 'o') { canvasConfig.setEditMode(CanvasEditMode.OFFSET); modePartialBuffer = null; modePartialKey = null; repaint(); return; }

				InstrumentDataKey dataKey = new InstrumentDataKey(canvasConfig.getId(),projectData.getCursorT().get(),row);
				// Clear partial buffer if cursor moved to a different cell
				if (modePartialKey != null && !modePartialKey.equals(dataKey)) {
					modePartialBuffer = null;
					modePartialKey = null;
				}
				CanvasEditMode canvasMode = canvasConfig.getEditMode();
				if (canvasMode == CanvasEditMode.OFFSET) {
					return; // OFFSET mode: nudge-only via Alt+Up/Down, no char entry
				} else if (canvasMode == CanvasEditMode.VELOCITY || canvasMode == CanvasEditMode.PAN) {
					// V/P mode: only edit existing notes, no new note creation
					NoteCell cell = projectData.getInstrumentData().get(dataKey);
					if (cell == null) return;
					char cu = Character.toUpperCase(c);
					if (!isHexChar(cu)) return;
					if (modePartialBuffer != null && modePartialBuffer.length() == 1) {
						// Second hex char — commit
						int val = Integer.parseInt(modePartialBuffer + cu, 16);
						val = Math.max(0, Math.min(127, val));
						pushUndo();
						if (canvasMode == CanvasEditMode.VELOCITY) {
							projectData.getInstrumentData().put(dataKey, cell.withVelocity(val));
						} else {
							projectData.getInstrumentData().put(dataKey, cell.withPan(val));
						}
						modePartialBuffer = null;
						modePartialKey = null;
						if (!fileHasBeenModified.get()) { fileHasBeenModified.set(true); updateWindowTitle(); }
						// Auto-advance to next valued cell (wraps to first if none after current)
						Pair<Integer,Integer> advPair = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
						moveCursorToT(nextValuedT(projectData.getCursorT().get(), advPair.b, canvasConfig.getId(), true));
					} else {
						// First hex char — store partial
						modePartialBuffer = String.valueOf(cu);
						modePartialKey = dataKey;
					}
					repaint();
				} else {
					// NOTE mode — normal note entry
					NoteCell existing = projectData.getInstrumentData().get(dataKey);
					String token0 = existing != null ? existing.token : "";
					String token1 = token0+c;
					if (canvasConfig instanceof DrumCanvasConfig) {
						// Drum canvases accept any input; numeric values (0-127) play as direct MIDI notes
						pushUndo();
						int vel = existing != null ? existing.velocity : getInitialVelocity(projectData.getCursorT().get());
						int pan = existing != null ? existing.pan : NoteCell.DEFAULT_PAN;
						int ofs = existing != null ? existing.offsetMs : NoteCell.DEFAULT_OFFSET_MS;
						projectData.getInstrumentData().put(dataKey, new NoteCell(token1, vel, pan, ofs));
						updateAutoRepeatEnd();
						playPreviewNote(canvasConfig, token1, row);
						repaint();
					} else if (canvasConfig.willAccept(token1,row)) {
						pushUndo();
						int vel = existing != null ? existing.velocity : getInitialVelocity(projectData.getCursorT().get());
						int pan = existing != null ? existing.pan : NoteCell.DEFAULT_PAN;
						int ofs = existing != null ? existing.offsetMs : NoteCell.DEFAULT_OFFSET_MS;
						projectData.getInstrumentData().put(dataKey, new NoteCell(token1, vel, pan, ofs));
						updateAutoRepeatEnd();
						playPreviewNote(canvasConfig, token1, row);
						repaint();
					}
				}
			}
			
		}
		
		void cmdBracketLeft() {
			int cursor = projectData.getCursorT().get();
			if (projectData.getRepeatT0().get() == cursor) {
				projectData.getRepeatT0().set(-1);
			} else {
				projectData.getRepeatT0().set(cursor);
			}
			repaint();
		}

		void cmdBracketRight() {
			int cursor = projectData.getCursorT().get() + 1;
			if (projectData.getRepeatT1().get() == cursor) {
				projectData.getRepeatT1().set(-1);
			} else {
				projectData.getRepeatT1().set(cursor);
			}
			repaint();
		}

		void addTick() {
			if (projectData == null || !isInGrid) return;
			pushUndo();
			int t = projectData.getCursorT().get();
			// Shift event data: entries at t and beyond move right by 1
			for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
				if (!(c instanceof EventCanvasConfig)) continue;
				Map<Point, ControlEvent> evData = projectData.getEventData(c.getId());
				List<Map.Entry<Point, ControlEvent>> toShift = new ArrayList<>();
				for (Map.Entry<Point, ControlEvent> e : evData.entrySet())
					if (e.getKey().x >= t) toShift.add(e);
				toShift.sort((a, b) -> b.getKey().x - a.getKey().x); // descending — process high ticks first
				for (Map.Entry<Point, ControlEvent> e : toShift) {
					evData.remove(e.getKey());
					evData.put(new Point(e.getKey().x + 1, e.getKey().y), e.getValue());
				}
			}
			// Shift instrument data: entries at t and beyond move right by 1
			Map<InstrumentDataKey, NoteCell> instrData = projectData.getInstrumentData();
			List<Map.Entry<InstrumentDataKey, NoteCell>> instrToShift = new ArrayList<>();
			for (Map.Entry<InstrumentDataKey, NoteCell> e : instrData.entrySet())
				if (e.getKey().getTime() >= t) instrToShift.add(e);
			instrToShift.sort((a, b) -> b.getKey().getTime() - a.getKey().getTime()); // descending
			for (Map.Entry<InstrumentDataKey, NoteCell> e : instrToShift) {
				instrData.remove(e.getKey());
				instrData.put(new InstrumentDataKey(e.getKey().getInstrumentId(), e.getKey().getTime() + 1, e.getKey().getRow()), e.getValue());
			}
			updateTupletCache();
			updateMeasureLinePositions();
			repaint();
		}

		void deleteTick() {
			if (projectData == null || !isInGrid) return;
			pushUndo();
			int t = projectData.getCursorT().get();
			// Remove events at t; shift events after t left by 1
			for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
				if (!(c instanceof EventCanvasConfig)) continue;
				Map<Point, ControlEvent> evData = projectData.getEventData(c.getId());
				List<Map.Entry<Point, ControlEvent>> toProcess = new ArrayList<>();
				for (Map.Entry<Point, ControlEvent> e : evData.entrySet())
					if (e.getKey().x >= t) toProcess.add(e);
				toProcess.sort((a, b) -> a.getKey().x - b.getKey().x); // ascending — process low ticks first
				for (Map.Entry<Point, ControlEvent> e : toProcess) {
					evData.remove(e.getKey());
					if (e.getKey().x > t)
						evData.put(new Point(e.getKey().x - 1, e.getKey().y), e.getValue());
				}
			}
			// Remove notes at t; shift notes after t left by 1
			Map<InstrumentDataKey, NoteCell> instrData = projectData.getInstrumentData();
			List<Map.Entry<InstrumentDataKey, NoteCell>> instrToProcess = new ArrayList<>();
			for (Map.Entry<InstrumentDataKey, NoteCell> e : instrData.entrySet())
				if (e.getKey().getTime() >= t) instrToProcess.add(e);
			instrToProcess.sort((a, b) -> a.getKey().getTime() - b.getKey().getTime()); // ascending
			for (Map.Entry<InstrumentDataKey, NoteCell> e : instrToProcess) {
				instrData.remove(e.getKey());
				if (e.getKey().getTime() > t)
					instrData.put(new InstrumentDataKey(e.getKey().getInstrumentId(), e.getKey().getTime() - 1, e.getKey().getRow()), e.getValue());
			}
			updateTupletCache();
			updateMeasureLinePositions();
			repaint();
		}

		void ctrlZ() {
			if (projectData == null || undoStack.isEmpty()) return;
			redoStack.push(snapshot());
			applySnapshot(undoStack.pop());
		}

		void ctrlR() {
			if (projectData == null || redoStack.isEmpty()) return;
			undoStack.push(snapshot());
			applySnapshot(redoStack.pop());
		}

		void ctrlL() {
			if (isInGrid && !isTupletSelectionMode) {
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
				CanvasConfig lassoCanvas = projectData.getCanvases().getCanvases().get(lassoCanvasNumber);
				if (lassoCanvas instanceof EventCanvasConfig) {
					Map<Point,ControlEvent> evData = projectData.getEventData(lassoCanvas.getId());
					Map<Point,ControlEvent> copied = new HashMap<>();
					int minT = Integer.MAX_VALUE;
					int minR = Integer.MAX_VALUE;
					for (int t = tMin; t <= tMax; t++) {
						for (int r = rowMin; r <=rowMax; r++) {
							Point point = new Point(t,r);
							if (evData.containsKey(point)) {
								minT = t<minT?t:minT;
								minR = r<minR?r:minR;
								copied.put(point, evData.get(point));
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
					CanvasConfig canvas = projectData.getCanvases().getCanvases().get(p.a);
										
					Map<InstrumentDataKey,NoteCell> copied = new HashMap<>();
					int minT = Integer.MAX_VALUE;
					int maxR = Integer.MIN_VALUE;
					for (int t = tMin; t <= tMax; t++) {
						for (int r = rowMin; r <=rowMax; r++) {
							InstrumentDataKey dk = new InstrumentDataKey(canvas.getId(),t,r);
							if (projectData.getInstrumentData().containsKey(dk)) {
								minT = t<minT?t:minT;
								maxR = r>maxR?r:maxR;
								copied.put(dk,projectData.getInstrumentData().get(dk));
							}
						}
					}
					Map<InstrumentDataKey,NoteCell> normalized= new HashMap<>();
					for (InstrumentDataKey dk : copied.keySet()) {
						normalized.put(new InstrumentDataKey(dk.getInstrumentId(),dk.getTime()-minT,dk.getRow()-maxR),
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
			pushUndo();
			Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());

			int tMin = Math.min(projectData.getCursorT().get(),lassoT0);
			int tMax = Math.max(projectData.getCursorT().get(),lassoT0);
			int rowMin = Math.min(lassoRow0, p.b);
			int rowMax = Math.max(lassoRow0, p.b);
			for (int t = tMin; t <= tMax; t++) {
				for (int r = rowMin; r <=rowMax; r++) {
					CanvasConfig xCanvas = projectData.getCanvases().getCanvases().get(lassoCanvasNumber);
					if (xCanvas instanceof EventCanvasConfig) {
						projectData.getEventData(xCanvas.getId()).remove(new Point(t,r));
					} else {
						CanvasConfig canvas = projectData.getCanvases().getCanvases().get(p.a);
						InstrumentDataKey dk = new InstrumentDataKey(canvas.getId(),t,r);
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
				CanvasConfig vCanvas = projectData.getCanvases().getCanvases().get(p.a);
				if (vCanvas instanceof EventCanvasConfig) {
					//then draw events if there are any
					if (!eventClipboard.isEmpty()) {

					}
				} else {
					if (!instrumentClipboard.isEmpty()) {
						pushUndo();
						CanvasConfig canvasConfig = projectData.getCanvases().getCanvases().get(p.a);
						for (Entry<InstrumentDataKey, NoteCell> entry : instrumentClipboard.entrySet()) {
							int t = entry.getKey().getTime() + projectData.getCursorT().get();
							int r = entry.getKey().getRow() + p.b;
							InstrumentDataKey dk = new InstrumentDataKey(canvasConfig.getId(), t, r);
							if (canvasConfig.willAccept(entry.getValue().token, r)) {
								projectData.getInstrumentData().put(dk, entry.getValue());
							}
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
					int maxRelRow = projectData.getCanvases().getCanvases().get(i).getRowCount();
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
			if (isTupletSelectionMode) {
				isTupletSelectionMode = false;
				repaint();
				return;
			}
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
		void finalizeTupletSelection() {
			int t0 = Math.min(tupletSelectionT0, projectData.getCursorT().get());
			int tLast = Math.max(tupletSelectionT0, projectData.getCursorT().get()); // inclusive last cell
			int t1 = tLast + 1; // exclusive end: cursor cell IS included in the tuplet
			isTupletSelectionMode = false;
			if (t0 == tLast) { repaint(); return; } // single-cell selection (nothing to group)
			// Collision check: any existing tuplet that overlaps [t0, t1)?
			NavigableMap<Integer, TupletEvent> candidates = cachedTuplets.headMap(t1, false);
			boolean overlaps = candidates.entrySet().stream()
					.anyMatch(e -> e.getValue().getEndT() > t0);
			if (overlaps) { repaint(); return; }
			pendingTupletT0 = t0;
			pendingTupletT1 = t1;
			enterInlineMode(tupletEventPanel);
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
		
		void drumNoteUp()   { adjustDrumNote(+1); }
		void drumNoteDown() { adjustDrumNote(-1); }
		void adjustDrumNote(int delta) {
			if (projectData == null || !isInGrid) return;
			Pair<Integer,Integer> pair = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			CanvasConfig canvas = projectData.getCanvases().getCanvases().get(pair.a);
			if (!(canvas instanceof DrumCanvasConfig) && !(canvas instanceof StringCanvasConfig)) return;
			InstrumentDataKey dataKey = new InstrumentDataKey(canvas.getId(), projectData.getCursorT().get(), pair.b);
			NoteCell curCell = projectData.getInstrumentData().get(dataKey);
			if (curCell == null) return;
			CanvasEditMode mode = canvas.getEditMode();
			if (mode == CanvasEditMode.VELOCITY) {
				int vel = Math.max(0, Math.min(127, curCell.velocity + delta));
				pushUndo();
				projectData.getInstrumentData().put(dataKey, curCell.withVelocity(vel));
				if (!fileHasBeenModified.get()) { fileHasBeenModified.set(true); updateWindowTitle(); }
				repaint();
				return;
			}
			if (mode == CanvasEditMode.PAN) {
				int pan = Math.max(0, Math.min(127, curCell.pan + delta));
				pushUndo();
				projectData.getInstrumentData().put(dataKey, curCell.withPan(pan));
				if (!fileHasBeenModified.get()) { fileHasBeenModified.set(true); updateWindowTitle(); }
				repaint();
				return;
			}
			if (mode == CanvasEditMode.OFFSET) {
				pushUndo();
				projectData.getInstrumentData().put(dataKey, curCell.withOffsetMs(curCell.offsetMs + delta));
				if (!fileHasBeenModified.get()) { fileHasBeenModified.set(true); updateWindowTitle(); }
				repaint();
				return;
			}
			// NOTE mode — drum MIDI note nudge only
			if (!(canvas instanceof DrumCanvasConfig)) return;
			int note;
			try { note = Integer.parseInt(curCell.token); } catch (NumberFormatException e) { return; }
			note = Math.max(0, Math.min(127, note + delta));
			pushUndo();
			projectData.getInstrumentData().put(dataKey, curCell.withToken(String.valueOf(note)));
			playPreviewNote(canvas, String.valueOf(note), pair.b);
			repaint();
		}

		double getCellWidth() {
			return gridFontMetrics.stringWidth("88.");
		}

		int computeNeededHeight() {
			FontMetrics topMetrics = new Canvas().getFontMetrics(new Font("SansSerif", Font.PLAIN, 14));
			FontMetrics gridMetrics = gridFontMetrics;
			int topBarHeight = topMetrics.getMaxAscent();
			int rowHeight = gridMetrics.getMaxAscent() + 4;
			int unscaled = topBarHeight * 3 + 5;
			for (CanvasConfig canvas : projectData.getCanvases().getCanvases()) {
				if (canvas instanceof EventCanvasConfig) {
					unscaled += rowHeight * (2 + canvas.getRowCount());
				} else {
					unscaled += rowHeight * (3 + canvas.getRowCount());
				}
			}
			return (int)(unscaled * displayScale) + frame.getInsets().top + frame.getInsets().bottom;
		}
		
		public final int getMaxVisibleTime() {
			int t0 = projectData.getViewT().get();
			
			double deviceScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
			double tDelta = ((getWidth() * deviceScale / displayScale - trackStripWidth) / getCellWidth());
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
			NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(projectData.getPlaybackT0().get(),
					false);
			if (!headMap.isEmpty()) {
				Entry<Integer, Integer> last = headMap.lastEntry();
				projectData.getPlaybackT0().set(last.getKey());
			}
			while (projectData.getPlaybackT0().get() < projectData.getViewT().get() + scrollTimeMargin
					&& projectData.getViewT().get() > 0) {
				projectData.getViewT().getAndDecrement();
			}
			repaint();
		}

		public void playTToNextMeasure() {
			NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(projectData.getPlaybackT0().get(),
					false);
			if (!tailMap.isEmpty()) {
				Entry<Integer, Integer> next = tailMap.firstEntry();
				projectData.getPlaybackT0().set(next.getKey());
			}
			while (projectData.getPlaybackT0().get() > getMaxVisibleTime() - scrollTimeMargin) {
				projectData.getViewT().getAndIncrement();
			}
			repaint();
		}
		
		int nextValuedT(int fromT, int relRow, String canvasId, boolean forward) {
			int[] ts = projectData.getInstrumentData().keySet().stream()
				.filter(k -> k.getInstrumentId().equals(canvasId) && k.getRow() == relRow)
				.mapToInt(InstrumentDataKey::getTime)
				.sorted()
				.toArray();
			if (ts.length == 0) return fromT;
			if (forward) {
				for (int t : ts) if (t > fromT) return t;
				return ts[0]; // wrap to first
			} else {
				for (int i = ts.length - 1; i >= 0; i--) if (ts[i] < fromT) return ts[i];
				return ts[ts.length - 1]; // wrap to last
			}
		}

		int nearestValuedT(int fromT, int relRow, String canvasId) {
			int best = -1;
			for (InstrumentDataKey k : projectData.getInstrumentData().keySet()) {
				if (!k.getInstrumentId().equals(canvasId) || k.getRow() != relRow) continue;
				int t = k.getTime();
				if (best == -1
						|| Math.abs(t - fromT) < Math.abs(best - fromT)
						|| (Math.abs(t - fromT) == Math.abs(best - fromT) && t > best)) {
					best = t;
				}
			}
			return best; // -1 if row is empty
		}

		void moveCursorToT(int t) {
			projectData.getCursorT().set(t);
			int viewT  = projectData.getViewT().get();
			int maxVis = getMaxVisibleTime();
			int visW   = maxVis - viewT;
			if (t < viewT || t >= maxVis) {
				projectData.getViewT().set(Math.max(0, t - visW / 2));
			} else if (t >= maxVis - scrollTimeMargin) {
				projectData.getViewT().getAndIncrement();
			} else if (t < viewT + scrollTimeMargin && viewT > 0) {
				projectData.getViewT().getAndDecrement();
			}
		}

		int getMaxRow() {
			int maxRow = projectData.getCanvases().getCanvases().stream().mapToInt(a->a.getRowCount()).sum();
			return maxRow;
		}
		void handleGridMovement(CardinalDirection dir) {
			int maxRow = getMaxRow();
			Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasNum = p.a;
			int relativeRow = p.b;
			int maxSelectedRow = projectData.getCanvases().getCanvases().get(canvasNum).getRowCount();
			switch (dir) {
			case DOWN:
				if (isSelectionMode.get()) {
					if (relativeRow < maxSelectedRow-1) {
						projectData.getSelectedRow().incrementAndGet();
					}
				} else {
					projectData.getSelectedRow().updateAndGet(i->(i+1)%maxRow);
				}
				{
					Pair<Integer,Integer> newP = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
					CanvasConfig newCanvas = projectData.getCanvases().getCanvases().get(newP.a);
					CanvasEditMode newMode = newCanvas.getEditMode();
					if (!(newCanvas instanceof EventCanvasConfig) &&
							(newMode == CanvasEditMode.VELOCITY || newMode == CanvasEditMode.PAN
							|| newMode == CanvasEditMode.OFFSET)) {
						int nearest = nearestValuedT(projectData.getCursorT().get(), newP.b, newCanvas.getId());
						if (nearest != -1) moveCursorToT(nearest);
					}
				}
				repaint();
				break;
			case LEFT: {
				CanvasConfig leftCanvas = projectData.getCanvases().getCanvases().get(canvasNum);
				CanvasEditMode leftMode = leftCanvas.getEditMode();
				if (!(leftCanvas instanceof EventCanvasConfig) &&
						(leftMode == CanvasEditMode.VELOCITY || leftMode == CanvasEditMode.PAN
						|| leftMode == CanvasEditMode.OFFSET)) {
					moveCursorToT(nextValuedT(projectData.getCursorT().get(), relativeRow, leftCanvas.getId(), false));
					repaint();
					break;
				}
				int visColsL = getMaxVisibleTime() - projectData.getViewT().get();
				boolean wasVisibleL = projectData.getCursorT().get() >= projectData.getViewT().get()
						&& projectData.getCursorT().get() < getMaxVisibleTime();
				projectData.getCursorT().updateAndGet(i -> Math.max(0, i - 1));
				if (!wasVisibleL) {
					projectData.getViewT().set(Math.max(0, projectData.getCursorT().get() - visColsL / 2));
				} else if (projectData.getCursorT().get() < projectData.getViewT().get() + scrollTimeMargin
						&& projectData.getViewT().get() > 0) {
					projectData.getViewT().getAndDecrement();
				}
				repaint();
				break;
			}
			case RIGHT: {
				CanvasConfig rightCanvas = projectData.getCanvases().getCanvases().get(canvasNum);
				CanvasEditMode rightMode = rightCanvas.getEditMode();
				if (!(rightCanvas instanceof EventCanvasConfig) &&
						(rightMode == CanvasEditMode.VELOCITY || rightMode == CanvasEditMode.PAN
						|| rightMode == CanvasEditMode.OFFSET)) {
					moveCursorToT(nextValuedT(projectData.getCursorT().get(), relativeRow, rightCanvas.getId(), true));
					repaint();
					break;
				}
				int maxVisR = getMaxVisibleTime();
				int visColsR = maxVisR - projectData.getViewT().get();
				boolean wasVisibleR = projectData.getCursorT().get() >= projectData.getViewT().get()
						&& projectData.getCursorT().get() < maxVisR;
				projectData.getCursorT().incrementAndGet();
				if (!wasVisibleR) {
					projectData.getViewT().set(Math.max(0, projectData.getCursorT().get() - visColsR / 2));
				} else if (projectData.getCursorT().get() >= getMaxVisibleTime()) {
					projectData.getViewT().getAndIncrement();
				}
				repaint();
				break;
			}
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
				{
					Pair<Integer,Integer> newP = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
					CanvasConfig newCanvas = projectData.getCanvases().getCanvases().get(newP.a);
					CanvasEditMode newMode = newCanvas.getEditMode();
					if (!(newCanvas instanceof EventCanvasConfig) &&
							(newMode == CanvasEditMode.VELOCITY || newMode == CanvasEditMode.PAN
							|| newMode == CanvasEditMode.OFFSET)) {
						int nearest = nearestValuedT(projectData.getCursorT().get(), newP.b, newCanvas.getId());
						if (nearest != -1) moveCursorToT(nearest);
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
			if (activeInlinePanel instanceof TextInputPanel) return;
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
			if (isTupletSelectionMode) {
				finalizeTupletSelection();
				return;
			}
			if (isInGrid) {
				if (!instrumentClipboard.isEmpty() || !eventClipboard.isEmpty()) {
					ctrlV();
				}
				return;
			}
			switch (sequencePosition) {
			case NEW:
				mainInterfacePanel.newProject();
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
			case UNDO:
				ctrlZ();
				break;
			case REDO:
				ctrlR();
				break;
			case SETTINGS:
				settingsPanel.refresh();
				cardLayout.show(cardPanel, settingsCardKey);
				break;
			case HELP:
				cardLayout.show(cardPanel, helpCardKey);
				break;
			case TAPPER:
				handleTapperTap();
				break;
			case FOLLOW:
				followPlayback = !followPlayback;
				break;
			case SET_REPEAT_START:
				cmdBracketLeft();
				break;
			case SET_REPEAT_END:
				cmdBracketRight();
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
			for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
				if (c instanceof EventCanvasConfig) {
					for (Point p : projectData.getEventData(c.getId()).keySet()) {
						lastT = Math.max(lastT, p.x);
					}
				}
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
			int i = -1;
			for (CanvasConfig a : projectData.getCanvases().getCanvases()) {
				i += a.getRowCount();
				rowBreaks.add(i);
			}
		}
		
		int getInitialVelocity(int newT) {
			// First: max velocity of all notes at the same time tick
			int maxAtT = -1;
			for (Map.Entry<InstrumentDataKey, NoteCell> e : projectData.getInstrumentData().entrySet()) {
				if (e.getKey().getTime() == newT)
					maxAtT = Math.max(maxAtT, e.getValue().velocity);
			}
			if (maxAtT >= 0) return maxAtT;
			// Second: velocity of most recent note before newT (max velocity at that tick)
			int closestT = -1;
			for (InstrumentDataKey k : projectData.getInstrumentData().keySet()) {
				if (k.getTime() < newT && k.getTime() > closestT) closestT = k.getTime();
			}
			if (closestT >= 0) {
				int maxV = 0;
				for (Map.Entry<InstrumentDataKey, NoteCell> e : projectData.getInstrumentData().entrySet()) {
					if (e.getKey().getTime() == closestT) maxV = Math.max(maxV, e.getValue().velocity);
				}
				return maxV;
			}
			return NoteCell.DEFAULT_VELOCITY;
		}

		boolean isHexChar(char c) {
			return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
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
			FontMetrics gridMetrics = g.getFontMetrics(gridFont);
			Font topFont = textFont;
			FontMetrics topFontMetrics = g.getFontMetrics(topFont);
			g.setFont(topFont);
			int topBarHeight = topFontMetrics.getMaxAscent();
			lastTopBarHeight = topBarHeight + topFontMetrics.getMaxDescent();
			menuItemBounds.clear();
			Iterator<Double> hueIterator = DoubleStream.iterate(0f, i->i+0.07).iterator();
			Runnable iterateHue = () -> {
				g.setPaint(Color.getHSBColor(hueIterator.next().floatValue(),0.5f,1.0f));
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
			boolean canUndo = !undoStack.isEmpty();
			Rectangle2D undoBounds = topFontMetrics.getStringBounds("UNDO", g);
			undoBounds = at.createTransformedShape(undoBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.UNDO, undoBounds);
			at.translate(undoBounds.getWidth() + 5, 0);
			g.setPaint(!isInGrid && sequencePosition == SequencePosition.UNDO ? Color.GRAY : Color.BLACK);
			g.fill(undoBounds);
			iterateHue.run();
			g.setPaint(canUndo ? Color.WHITE : new Color(80, 80, 80));
			g.drawString("UNDO", (int) undoBounds.getMinX(), (int) undoBounds.getMaxY());
			iterateHue.run();
			boolean canRedo = !redoStack.isEmpty();
			Rectangle2D redoBounds = topFontMetrics.getStringBounds("REDO", g);
			redoBounds = at.createTransformedShape(redoBounds).getBounds2D();
			menuItemBounds.put(SequencePosition.REDO, redoBounds);
			at.translate(redoBounds.getWidth() + 5, 0);
			g.setPaint(!isInGrid && sequencePosition == SequencePosition.REDO ? Color.GRAY : Color.BLACK);
			g.fill(redoBounds);
			iterateHue.run();
			g.setPaint(canRedo ? Color.WHITE : new Color(80, 80, 80));
			g.drawString("REDO", (int) redoBounds.getMinX(), (int) redoBounds.getMaxY());
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
			String followString = followPlayback ? "FOLLOW:ON" : "FOLLOW:OFF";
			Rectangle2D followBoundsRaw = topFontMetrics.getStringBounds(followString, g);
			Rectangle2D followBounds_ = at.createTransformedShape(followBoundsRaw).getBounds2D();
			menuItemBounds.put(SequencePosition.FOLLOW, followBounds_);
			if (!isInGrid && sequencePosition == SequencePosition.FOLLOW) { g.setPaint(Color.GRAY); g.fill(followBounds_); }
			iterateHue.run();
			g.drawString(followString,(int) followBounds_.getMinX(),(int) followBounds_.getMaxY());
			at.translate(followBounds_.getWidth()+5,0);
			// Repeat start: thin bar (left) + thick bar (right) — musical "begin repeat" symbol
			// Use negative y so at.createTransformedShape aligns with the text baseline (same as string bounds convention)
			int repW = 10;
			Rectangle2D repeatStartBounds = at.createTransformedShape(new java.awt.geom.Rectangle2D.Double(0, -topBarHeight, repW, lastTopBarHeight)).getBounds2D();
			menuItemBounds.put(SequencePosition.SET_REPEAT_START, repeatStartBounds);
			boolean rsOn = projectData.getRepeatT0().get() >= 0;
			if (!isInGrid && sequencePosition == SequencePosition.SET_REPEAT_START) { g.setPaint(Color.GRAY); g.fill(repeatStartBounds); }
			g.setPaint(rsOn ? new Color(0xBF, 0x57, 0x00) : Color.WHITE);
			{
				// Use tight glyph bounds so bars align with the actual visual top/bottom of capital letters,
				// not the padded maxAscent which sits above the glyphs.
				java.awt.font.GlyphVector capGV = topFont.createGlyphVector(g.getFontRenderContext(), "N");
				Rectangle2D capBounds = capGV.getGlyphVisualBounds(0).getBounds2D();
				int barTop    = (int) Math.ceil(topBarHeight + capBounds.getY());
				int barBottom = lastTopBarHeight;
				int barH      = barBottom - barTop;
				int rx = (int)repeatStartBounds.getMinX();
				g.fillRect(rx, barTop, 4, barH);               // fat bar LEFT (start repeat)
				g.drawLine(rx + 6, barTop, rx + 6, barBottom); // thin bar right
				// dots on right (traditional begin-repeat colon)
				g.fillOval(rx + 8, barTop + barH / 4 - 1, 2, 2);
				g.fillOval(rx + 8, barTop + 3 * barH / 4 - 1, 2, 2);
			}
			at.translate(repW + 5, 0);
			// Repeat end: thin bar (left) + fat bar (right) — musical "end repeat" symbol
			Rectangle2D repeatEndBounds = at.createTransformedShape(new java.awt.geom.Rectangle2D.Double(0, -topBarHeight, repW, lastTopBarHeight)).getBounds2D();
			menuItemBounds.put(SequencePosition.SET_REPEAT_END, repeatEndBounds);
			boolean reOn = projectData.getRepeatT1().get() >= 0;
			if (!isInGrid && sequencePosition == SequencePosition.SET_REPEAT_END) { g.setPaint(Color.GRAY); g.fill(repeatEndBounds); }
			g.setPaint(reOn ? new Color(0xBF, 0x57, 0x00) : Color.WHITE);
			{
				java.awt.font.GlyphVector capGV = topFont.createGlyphVector(g.getFontRenderContext(), "N");
				Rectangle2D capBounds = capGV.getGlyphVisualBounds(0).getBounds2D();
				int barTop    = (int) Math.ceil(topBarHeight + capBounds.getY());
				int barBottom = lastTopBarHeight;
				int barH      = barBottom - barTop;
				int rx2 = (int)repeatEndBounds.getMinX();
				// dots on left (traditional end-repeat colon)
				g.fillOval(rx2, barTop + barH / 4 - 1, 2, 2);
				g.fillOval(rx2, barTop + 3 * barH / 4 - 1, 2, 2);
				g.drawLine(rx2 + 3, barTop, rx2 + 3, barBottom);       // thin bar (shifted right)
				g.fillRect(rx2 + 5, barTop, 4, barH);                  // fat bar RIGHT (end repeat)
			}

			at.setToIdentity();

			String helpString = "HELP";
			Rectangle2D helpBoundsRaw = topFontMetrics.getStringBounds(helpString, g);
			AffineTransform atHelp = new AffineTransform();
			atHelp.translate(W - helpBoundsRaw.getWidth() - 5, topBarHeight);
			Rectangle2D helpBounds = atHelp.createTransformedShape(helpBoundsRaw).getBounds2D();
			menuItemBounds.put(SequencePosition.HELP, helpBounds);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.HELP?Color.GRAY:Color.BLACK);
			g.fill(helpBounds);
			iterateHue.run();
			g.drawString(helpString,(int) helpBounds.getMinX(),(int) helpBounds.getMaxY());

			int sbH = 9; // scrollbar strip height in drawing coords
			int sbY = lastTopBarHeight + 2; // top of scrollbar strip
			at.translate(0, sbY + sbH + 2); // just past scrollbar border; each canvas prepends its own 5px gap

			int cellWidth = (int) getCellWidth();
			int rowHeight = gridMetrics.getMaxAscent()+4;
			trackStripWidth = 3 * (cellWidth + 4); // +4 per column so labels have room to center
			int tDelta = (int) ((W - trackStripWidth) / cellWidth);
			double subTickOffset = 0.0;
			int t0;
			if (isPlaying.get() && followPlayback && smoothTickDurationNanos > 0) {
				long now = System.nanoTime();
				double fraction = (double)(now - smoothTickStartNanos) / (double)smoothTickDurationNanos;
				fraction = Math.max(0.0, Math.min(1.5, fraction));
				double smoothPlaybackT = smoothTickBase + fraction;
				int cursorCol = tDelta / 4;
				double viewTFloat = smoothPlaybackT - cursorCol;
				if (viewTFloat < 0) viewTFloat = 0;
				t0 = (int)Math.floor(viewTFloat);
				subTickOffset = (viewTFloat - t0) * cellWidth;
			} else {
				t0 = projectData.getViewT().get();
			}
			int t1 = t0 + tDelta + 1;

			// --- Horizontal scrollbar (between menu bar and grid) ---
			{
				int maxEventT = t1;
				for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
					if (c instanceof EventCanvasConfig) {
						for (Point p : projectData.getEventData(c.getId()).keySet()) {
							maxEventT = Math.max(maxEventT, p.x + 1);
						}
					}
				}
				for (InstrumentDataKey k : projectData.getInstrumentData().keySet()) {
					maxEventT = Math.max(maxEventT, k.getTime() + 1);
				}
				// dead area = black (from overall fill); just store bounds for click detection
				lastScrollbarBounds = new Rectangle2D.Double(0, sbY, W, sbH);
				// white lines above and below
				g.setPaint(Color.WHITE);
				g.drawLine(0, sbY - 1, (int)W, sbY - 1);
				g.drawLine(0, sbY + sbH + 1, (int)W, sbY + sbH + 1);
				if (maxEventT > 0) {
					double thumbX0 = (double) t0 / maxEventT * W;
					double thumbX1 = Math.min((double) t1 / maxEventT * W, W);
					double thumbW = Math.max(4, thumbX1 - thumbX0);
					lastScrollbarThumbBounds = new Rectangle2D.Double(thumbX0, sbY, thumbW, sbH);
					// rainbow gradient: 7x1 pastel raster drawn with bilinear scaling
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g.drawImage(scrollbarRainbowImage, (int)thumbX0, sbY, (int)thumbW, sbH, null);
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
					// event markers: thin colored lines per event type (no tuplets)
					for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
						if (c instanceof EventCanvasConfig) {
							for (Map.Entry<Point, ControlEvent> me : projectData.getEventData(c.getId()).entrySet()) {
								ControlEventType et = me.getValue().getType();
								if (et == ControlEventType.TUPLET) continue;
								Color mc;
								switch (et) {
									case TEMPO:          mc = C_SB_TEMPO;          break;
									case TIME_SIGNATURE: mc = C_SB_TIME_SIG;       break;
									case SHUFFLE:        mc = C_SB_SHUFFLE;        break;
									case PROGRAM_CHANGE: mc = C_SB_PROGRAM_CHANGE; break;
									case TUNING_CHANGE:  mc = C_SB_TUNING_CHANGE;  break;
									case STICKY_NOTE:    mc = C_SB_STICKY_NOTE;    break;
									default:             mc = Color.WHITE;         break;
								}
								g.setPaint(mc);
								int markerX = (int)((double) me.getKey().x / maxEventT * W);
								g.fillRect(markerX, sbY, 2, sbH);
							}
						}
					}
					// playbackT0 marker — diagonal olive texture
					{
						int pt0 = projectData.getPlaybackT0().get();
						int pt0X = (int)((double) pt0 / maxEventT * W);
						g.setPaint(olivePaintScrollbar);
						g.fillRect(pt0X, sbY, 4, sbH);
					}
					// repeat markers — amber, same color as grid/top-bar repeat bars
					g.setPaint(new Color(0xBF, 0x57, 0x00));
					int dot25Y = sbY + 1; // center at sbY + 2.25 → top-left of 2x2
					int dot75Y = sbY + 6; // center at sbY + 6.75 → top-left of 2x2
					if (projectData.getRepeatT0().get() >= 0) {
						int r0X = (int)((double) projectData.getRepeatT0().get() / maxEventT * W);
						g.fillRect(r0X, sbY, 2, sbH);          // bar
						g.fillOval(r0X + 3, dot25Y, 2, 2);     // dot upper-right
						g.fillOval(r0X + 3, dot75Y, 2, 2);     // dot lower-right
					}
					if (projectData.getRepeatT1().get() >= 0) {
						int r1X = (int)((double) projectData.getRepeatT1().get() / maxEventT * W);
						g.fillOval(r1X - 4, dot25Y, 2, 2);     // dot upper-left
						g.fillOval(r1X - 4, dot75Y, 2, 2);     // dot lower-left
						g.fillRect(r1X, sbY, 2, sbH);          // bar
					}
				}
			}

			g.setPaint(Color.WHITE);
			g.setFont(gridFont);
			Area clip = new Area(new Rectangle2D.Double(0, 0, W, H));
			clip.subtract(new Area(new Rectangle2D.Double(0, 0, W, sbY + sbH + 3)));
			g.setClip(clip);
			List<Shape> canvasGrids= new ArrayList<>();
			Map<String,Point2D> stringPositions = new HashMap<>();
			List<Rectangle2D> measurePanels = new ArrayList<>();
			int x = 0;
			for (CanvasConfig canvasConfig : projectData.getCanvases().getCanvases()) {
				int canvasRowCount = canvasConfig.getRowCount();
				String canvasLabelString = canvasConfig.getName();
				at.translate(0, 5); // 5px gap above each canvas label (from scrollbar or previous measure panel)
				if (canvasConfig instanceof EventCanvasConfig) {
					// Event canvas: single row height above grid (like old Events label)
					Rectangle2D labelBounds = topFontMetrics.getStringBounds(canvasLabelString, g);
					labelBounds = at.createTransformedShape(labelBounds).getBounds2D();
					at.translate(0, rowHeight);
					stringPositions.put(canvasLabelString, new Point2D.Double(
							labelBounds.getMinX(),
							at.getTranslateY() - topFontMetrics.getMaxDescent() - 1));
				} else {
					// Instrument canvas: one row height above grid for the label
					Rectangle2D labelBounds = gridMetrics.getStringBounds(canvasLabelString, g);
					labelBounds = at.createTransformedShape(labelBounds).getBounds2D();
					at.translate(0, rowHeight);
					stringPositions.put(canvasLabelString, new Point2D.Double(
							labelBounds.getMinX(),
							at.getTranslateY() - gridMetrics.getMaxDescent() - 1));
				}

				Path2D.Double p2d = new Path2D.Double();
				x = (int)(trackStripWidth - subTickOffset);
				for (int t = t0; t <= t1; t++) {
					p2d.append(new Line2D.Double(
							new Point2D.Double(x, 0),
							new Point2D.Double(x, rowHeight * canvasRowCount)), false);
					x += cellWidth;
				}
				for (int r = -1; r < canvasRowCount; r++) {
					double y = (r + 1) * rowHeight;
					p2d.append(new Line2D.Double(
							new Point2D.Double(trackStripWidth, y),
							new Point2D.Double(W, y)), false);
				}

				canvasGrids.add(at.createTransformedShape(p2d));
				at.translate(0, p2d.getBounds2D().getHeight());
				Rectangle2D measuresPanel = new Rectangle2D.Double(0, 0, W, rowHeight);
				measurePanels.add(at.createTransformedShape(measuresPanel).getBounds2D());
				at.translate(0, measuresPanel.getHeight());
			}

			lastCanvasGrids = new ArrayList<>(canvasGrids);
			lastCellWidth = cellWidth;
			lastRowHeight = rowHeight;
			lastT1 = t1;

			Rectangle2D selectedGridBounds = new Rectangle2D.Double(0,0,1,1);
			Rectangle2D selectionRectangle = new Rectangle2D.Double(0,0,1,1);
			Pair<Integer,Integer> pair = 
					getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int canvasGridNum = pair.a;
			int relativeRow = pair.b;
			{
				
				selectedGridBounds = canvasGrids.get(canvasGridNum).getBounds2D();
				
				double selectedX = trackStripWidth +
						(projectData.getCursorT().get()-t0)*cellWidth - subTickOffset;
				double selectedY = selectedGridBounds.getMinY()+rowHeight*relativeRow;
				
				selectionRectangle = new Rectangle2D.Double(selectedX,selectedY,cellWidth,rowHeight);
				
			}
						
			lastVerticalTranslate = (int) Math.min(0, H-selectionRectangle.getMaxY()-rowHeight*2) - viewY;
			g.translate(0, lastVerticalTranslate);
			stringPositions.entrySet().forEach(entry -> {
				g.drawString(entry.getKey(), (int) entry.getValue().getX() + trackStripWidth, (int) entry.getValue().getY());
			});
			
			// Compute smooth playback screen x once, reuse per canvas
			double smoothPlayX;
			if (isPlaying.get() && smoothTickDurationNanos > 0) {
				long nowNs = System.nanoTime();
				double frac = (double)(nowNs - smoothTickStartNanos) / (double)smoothTickDurationNanos;
				frac = Math.max(0.0, Math.min(1.5, frac));
				double spt = smoothTickBase + frac;
				smoothPlayX = trackStripWidth + (spt - t0) * cellWidth - subTickOffset;
			} else {
				smoothPlayX = (projectData.getPlaybackT().get() - t0) * cellWidth + trackStripWidth - subTickOffset;
			}
			for (Shape canvasGrid : canvasGrids) {
				Rectangle2D bounds = canvasGrid.getBounds2D();
				if (isPlaying.get()) {
					// During playback: smooth 2px line tracking real-time position
					g.setPaint(C_PLAYBACK_LINE);
					g.setStroke(STROKE_2);
					g.draw(new Line2D.Double(smoothPlayX, bounds.getMinY(), smoothPlayX, bounds.getMaxY()));
					g.setStroke(STROKE_1);
				}
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
							bounds.getMinX()+(p.x-t0)*cellWidth - subTickOffset,
							bounds.getMinY()+(p.y)*rowHeight,
							cellWidth,rowHeight));

				}
				g.setPaint(Color.GREEN.darker());
				for (Point p : innerSelectionCells) {
					g.fill(new Rectangle2D.Double(
							bounds.getMinX()+(p.x-t0)*cellWidth - subTickOffset,
							bounds.getMinY()+(p.y)*rowHeight,
							cellWidth,rowHeight));
							
				}
				
			}
			
			// Tuplet selection mode preview
			if (isTupletSelectionMode && tupletSelectionCanvasId != null) {
				for (int ci = 0; ci < projectData.getCanvases().getCanvases().size(); ci++) {
					CanvasConfig tsc = projectData.getCanvases().getCanvases().get(ci);
					if (tsc.getId().equals(tupletSelectionCanvasId)) {
						Rectangle2D tb = canvasGrids.get(ci).getBounds2D();
						int sT0 = Math.min(tupletSelectionT0, projectData.getCursorT().get());
						int sT1 = Math.max(tupletSelectionT0, projectData.getCursorT().get());
						int tRow = tupletSelectionRow;
						g.setPaint(C_TUPLET_BG_SEL);
						for (int tt = sT0; tt <= sT1; tt++) {
							int tx = (int)(trackStripWidth + (tt - t0) * cellWidth - subTickOffset);
							g.fillRect(tx, (int)(tb.getMinY() + tRow * rowHeight), cellWidth, rowHeight);
						}
						break;
					}
				}
			}

			// rbFractions and rbColors are cached fields, rebuilt only when rainbowBrightness changes
			// Mode tint overlay (drawn before content, behind notes)
			java.awt.Composite prevComposite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.13f));
			for (int ci = 0; ci < projectData.getCanvases().getCanvases().size(); ci++) {
				CanvasConfig tintCanvas = projectData.getCanvases().getCanvases().get(ci);
				if (tintCanvas instanceof StringCanvasConfig || tintCanvas instanceof DrumCanvasConfig) {
					CanvasEditMode tintMode = tintCanvas.getEditMode();
					Color tint = null;
					if (tintMode == CanvasEditMode.VELOCITY) tint = new Color(255, 220, 0);
					else if (tintMode == CanvasEditMode.PAN) tint = new Color(220, 0, 220);
					else if (tintMode == CanvasEditMode.OFFSET) tint = new Color(0, 220, 220);
					if (tint != null) {
						Rectangle2D tb = canvasGrids.get(ci).getBounds2D();
						g.setPaint(tint);
						g.fill(tb);
					}
				}
			}
			g.setComposite(prevComposite);
			{
				java.awt.Paint rainbowPaint = getRainbowPaint((float)W);
				List<CanvasConfig> allCanvases = projectData.getCanvases().getCanvases();
				for (int ci = 0; ci < canvasGrids.size(); ci++) {
					CanvasConfig cc = allCanvases.get(ci);
					java.awt.Paint gridPaint = rainbowPaint;
					if (cc instanceof StringCanvasConfig || cc instanceof DrumCanvasConfig) {
						CanvasEditMode gm = cc.getEditMode();
						if (gm == CanvasEditMode.VELOCITY)     gridPaint = new Color(255, 220, 0);
						else if (gm == CanvasEditMode.PAN)     gridPaint = new Color(160, 0, 220);
						else if (gm == CanvasEditMode.OFFSET)  gridPaint = new Color(236, 88, 0);
					}
					g.setPaint(gridPaint);
					g.draw(canvasGrids.get(ci));
				}
			}
			g.setPaint(Color.RED);
			g.draw(selectedGridBounds);
			g.setPaint(Color.RED);
			g.draw(selectionRectangle);
			int canvasNumber = 0;
			g.setFont(gridFont);
			for (Shape canvasGrid : canvasGrids) {				
				
				Rectangle2D bounds = canvasGrid.getBounds2D();
				int playbackStartX = (int)((projectData.getPlaybackStartT().get()-t0)*cellWidth + trackStripWidth - subTickOffset);

				g.setPaint(Color.orange.darker());
				g.setStroke(STROKE_1);
				g.draw(new Line2D.Double(playbackStartX, bounds.getMinY(), playbackStartX,bounds.getMaxY()));
				g.draw(new Line2D.Double(playbackStartX+3, bounds.getMinY(), playbackStartX+3,bounds.getMaxY()));

				int pt0X = (int)((projectData.getPlaybackT0().get()-t0)*cellWidth + trackStripWidth - subTickOffset);
				{
					g.setPaint(olivePaintGrid);
					g.fill(new Rectangle2D.Double(pt0X, bounds.getMinY(), cellWidth, bounds.getHeight()));
				}


				x = (int)(trackStripWidth - subTickOffset);
				g.setPaint(Color.white);

				CanvasConfig renderCanvas = projectData.getCanvases().getCanvases().get(canvasNumber);
				String canvasName = renderCanvas.getName();
				String canvasId   = renderCanvas.getId();
				int rowCount = renderCanvas.getRowCount();

				// Inline event entry: replace cell area with the active entry form
				if (activeInlinePanel != null && canvasNumber == canvasGridNum
						&& renderCanvas instanceof EventCanvasConfig) {
					activeInlinePanel.paintInline(g,
							(int) trackStripWidth,
							(int) bounds.getMinY(),
							(int) (W - trackStripWidth),
							(int) bounds.getHeight());
					canvasNumber++;
					continue;
				}

				Map<Point, ControlEvent> renderEvData = (renderCanvas instanceof EventCanvasConfig)
						? projectData.getEventData(canvasId) : null;
				Point lookupPt = new Point(); // reused for all renderEvData map lookups in this canvas
				for (int t = t0; t<t1; t++) {

					for (int row = 0; row < rowCount; row++) {
						if (renderCanvas instanceof EventCanvasConfig) {
							// Tuplet range background shading
							TupletEvent tuplRender = getTupletAt(t);
							if (tuplRender != null) {
								Integer tuplT0Render = cachedTuplets.floorKey(t);
								lookupPt.setLocation(tuplT0Render, row);
								ControlEvent tuplEv = renderEvData.get(lookupPt);
								if (tuplEv != null && tuplEv.getType() == ControlEventType.TUPLET) {
									g.setPaint(C_TUPLET_BG);
									g.fillRect(x, (int)(bounds.getMinY() + row * rowHeight), cellWidth, rowHeight);
								}
							}
							lookupPt.setLocation(t, row);
							ControlEvent event = renderEvData.get(lookupPt);
							if (event != null) {
								switch (event.getType()) {
								
								case TIME_SIGNATURE: {
									g.setFont(italicGridFont);
									g.setPaint(Color.YELLOW);
									String text = event.toString();
									g.drawString(text,
											x+(rowHeight-gridMetrics.stringWidth(text))/2,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									
									break;
								}
								case PROGRAM_CHANGE: {
									g.setFont(italicGridFont);
									g.setPaint(C_PROGRAM_CHANGE);
									String text = event.toString();
									g.drawString(text,
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
											
									g.setFont(gridFont);
									break;
								}								
								case TEMPO: {
									g.setFont(italicGridFont);
									g.setPaint(C_TEMPO);
									String text = event.toString();
									g.drawString(text,
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									g.setFont(gridFont);
									break;
								}
								case SHUFFLE: {
									g.setFont(italicGridFont);
									g.setPaint(C_SHUFFLE_EV);
									g.drawString(event.toString(),
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									g.setFont(gridFont);
									break;
								}								
								case STICKY_NOTE: {
									g.setFont(italicGridFont);
									g.setPaint(C_STICKY_NOTE);
									String text = ((StickyNote) event).getText();
									g.drawString(text,
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									break;
								}
								case TUNING_CHANGE: {
									g.setFont(italicGridFont);
									g.setPaint(C_TUNING_CHANGE);
									g.drawString(event.toString(),
											x,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									g.setFont(gridFont);
									break;
								}
								case TUPLET:
									// Background shading handled above; no additional text rendering here
									break;
								default:
									break;
								}					
							}					
						} else {
							InstrumentDataKey dataKey = new InstrumentDataKey(canvasId,t,row);
							NoteCell cell = projectData.getInstrumentData().get(dataKey);
							if (cell != null) {
								CanvasConfig renderCanvasConfig = projectData.getCanvases().getCanvases().get(canvasNumber);
								CanvasEditMode renderMode = renderCanvasConfig.getEditMode();
								String displayVal;
								Color displayColor = Color.WHITE;
								if (renderMode == CanvasEditMode.VELOCITY) {
									if (modePartialKey != null && modePartialKey.equals(dataKey) && modePartialBuffer != null) {
										displayVal = modePartialBuffer + "_";
										displayColor = Color.WHITE;
									} else {
										displayVal = String.format("%02X", cell.velocity);
										float velHue = cell.velocity / 128f * (5f / 6f);
										displayColor = Color.getHSBColor(velHue, 0.5f, (float) rainbowBrightness);
									}
								} else if (renderMode == CanvasEditMode.PAN) {
									if (modePartialKey != null && modePartialKey.equals(dataKey) && modePartialBuffer != null) {
										displayVal = modePartialBuffer + "_";
									} else {
										displayVal = String.format("%02X", cell.pan);
									}
								} else if (renderMode == CanvasEditMode.OFFSET) {
									int ofs = cell.offsetMs;
									displayVal = ofs == 0 ? "0" : (ofs > 0 ? "+" + ofs : String.valueOf(ofs));
								} else {
									displayVal = cell.token;
									// Validate token — red if invalid
									boolean valid = true;
									if (renderCanvasConfig instanceof DrumCanvasConfig) {
										DrumCanvasConfig dc = (DrumCanvasConfig) renderCanvasConfig;
										int resolved = -1;
										try { resolved = Integer.parseInt(cell.token); } catch (NumberFormatException ignored) {}
										if (resolved < 0 || resolved > 127) resolved = dc.getMidiNumber(cell.token).orElse(-1);
										valid = resolved >= 0 || cell.token.isEmpty();
									} else if (renderCanvasConfig instanceof StringCanvasConfig) {
										valid = cell.token.equals("-") || ((StringCanvasConfig) renderCanvasConfig).getFrequency(cell.token, row).isPresent();
									}
									displayColor = valid ? Color.WHITE : Color.RED;
								}
								g.setPaint(displayColor);
								g.drawString(displayVal, x+(rowHeight-gridMetrics.stringWidth(displayVal))/2,
										(int) (bounds.getMinY()+(row+1)*rowHeight-2));
							}
						}
					}					
						
					// Tuplet internal sub-beat markers at storage cells k=1..B-1
					TupletEvent tuplHere = getTupletAt(t);
					if (tuplHere != null) {
						Integer tuplT0Here = cachedTuplets.floorKey(t);
						int k = t - tuplT0Here;
						if (k > 0 && k < tuplHere.getSubdivisions()) {
							g.setStroke(STROKE_1);
							g.setPaint(C_TUPLET_MARKER);
							g.draw(new Line2D.Double(x, bounds.getMinY(), x, bounds.getMaxY()));
						}
					}
					x += cellWidth;
				}

				// Tuplet labels drawn after all fills so they aren't overwritten
				if (renderEvData != null) {
					g.setFont(italicGridFont);
					g.setPaint(Color.WHITE);
					for (int row = 0; row < rowCount; row++) {
						// tuplets whose start is visible
						for (Map.Entry<Integer, TupletEvent> te : cachedTuplets.subMap(t0, true, t1, false).entrySet()) {
							lookupPt.setLocation(te.getKey(), row);
							ControlEvent ev = renderEvData.get(lookupPt);
							if (ev == null || ev.getType() != ControlEventType.TUPLET) continue;
							int lx = (int)(trackStripWidth + (te.getKey() - t0) * cellWidth - subTickOffset);
							g.drawString(tupletLabel(te.getValue(), te.getKey()),
									lx, (int)(bounds.getMinY() + (row + 1) * rowHeight - 2));
						}
						// tuplet that started before view but spans into it — draw at left edge
						Map.Entry<Integer, TupletEvent> prior = cachedTuplets.lowerEntry(t0);
						if (prior != null && prior.getValue().getEndT() > t0) {
							lookupPt.setLocation(prior.getKey(), row);
							ControlEvent ev = renderEvData.get(lookupPt);
							if (ev != null && ev.getType() == ControlEventType.TUPLET) {
								g.drawString(tupletLabel(prior.getValue(), prior.getKey()),
										(int)trackStripWidth, (int)(bounds.getMinY() + (row + 1) * rowHeight - 2));
							}
						}
					}
					g.setFont(gridFont);
				}

				// Beat markers - real-time cell positions, may be fractional
				g.setFont(gridFont);
				for (double beatCell : cachedBeatMarkerCells) {
					if (beatCell < t0 || beatCell >= t1) continue;
					double mx = trackStripWidth + (beatCell - t0) * cellWidth - subTickOffset;
					g.setStroke(STROKE_1);
					g.setPaint(C_BEAT_MARKER);
					g.draw(new Line2D.Double(mx, bounds.getMinY(), mx, bounds.getMaxY()));
					g.setPaint(Color.white);
					g.drawString(".", (int) mx, (int)(bounds.getMaxY() + rowHeight));
				}
				// Measure lines - real-time cell positions, may be fractional
				for (Map.Entry<Double, Integer> me : cachedMeasureCells.entrySet()) {
					double cell = me.getKey();
					if (cell < t0 || cell >= t1) continue;
					double mx = trackStripWidth + (cell - t0) * cellWidth - subTickOffset;
					g.setStroke(STROKE_2);
					g.setPaint(C_MEASURE_LINE);
					g.draw(new Line2D.Double(mx, bounds.getMinY(), mx, bounds.getMaxY()));
					g.setPaint(Color.white);
					g.drawString("" + me.getValue(), (int) mx, (int)(bounds.getMaxY() + rowHeight));
				}
				// Repeat bars — drawn on top of grid and measure lines
				{
					g.setPaint(new Color(0xBF, 0x57, 0x00));
					double rY0 = bounds.getMinY() + 2;
					double rY1 = bounds.getMaxY() - 2;
					double rH  = rY1 - rY0;
					int dotD = 3;
					double dot25Y = rY0 + rH * 0.25 - dotD / 2.0;
					double dot75Y = rY0 + rH * 0.75 - dotD / 2.0;
					// begin repeat: thin bar | thick bar : (dots on right)
					if (projectData.getRepeatT0().get() >= 0) {
						int r0X = (int)((projectData.getRepeatT0().get()-t0)*cellWidth + trackStripWidth - subTickOffset);
						g.setStroke(STROKE_1);
						g.draw(new Line2D.Double(r0X,   rY0, r0X,   rY1));
						g.setStroke(STROKE_3);
						g.draw(new Line2D.Double(r0X+4, rY0, r0X+4, rY1));
						g.fillOval(r0X + 8, (int) dot25Y, dotD, dotD);
						g.fillOval(r0X + 8, (int) dot75Y, dotD, dotD);
					}
					// end repeat: : thick bar | thin bar (dots on left)
					if (projectData.getRepeatT1().get() >= 0) {
						int r1X = (int)((projectData.getRepeatT1().get()-t0)*cellWidth + trackStripWidth - subTickOffset);
						g.fillOval(r1X - 11, (int) dot25Y, dotD, dotD);
						g.fillOval(r1X - 11, (int) dot75Y, dotD, dotD);
						g.setStroke(STROKE_3);
						g.draw(new Line2D.Double(r1X-4, rY0, r1X-4, rY1));
						g.setStroke(STROKE_1);
						g.draw(new Line2D.Double(r1X,   rY0, r1X,   rY1));
					}
					g.setStroke(STROKE_1);
				}

				if (!isSelectionMode.get() && lassoCanvasNumber >= 0) {
					CanvasConfig curCanvas = projectData.getCanvases().getCanvases().get(canvasNumber);
					if (curCanvas instanceof EventCanvasConfig) {
						//then draw events if there are any
						if (!eventClipboard.isEmpty()) {

						}
					} else {
						if (canvasNumber == canvasGridNum && !instrumentClipboard.isEmpty()) {
							CanvasConfig canvasConfig = projectData.getCanvases().getCanvases().get(canvasNumber);
							CanvasConfig lassoCanvasConfig = projectData.getCanvases().getCanvases().get(lassoCanvasNumber);
							if (lassoCanvasConfig.getType() == canvasConfig.getType()) {
								g.setPaint(Color.RED);
								for (Entry<InstrumentDataKey, NoteCell> entry : instrumentClipboard.entrySet()) {
									int x0 = (int)((projectData.getCursorT().get()-t0)*cellWidth - subTickOffset + (int)bounds.getMinX());
									int y0 = (relativeRow+1)*rowHeight+(int)bounds.getMinY();
									int x_ = entry.getKey().getTime()*cellWidth+x0;
									int y_ = y0+entry.getKey().getRow()*rowHeight;
									if (y_ > bounds.getMinY()) {
										g.drawString(entry.getValue().token,x_+(cellWidth-gridFontMetrics.stringWidth(entry.getValue().token))/2,y_);
									}
								};								
							}

						}
					}
				}
				canvasNumber++;

			}

			// Track control strip — drawn as an overlay on the left edge of each canvas
			lastStripBounds.clear();
			g.setClip(null);
			g.setStroke(STROKE_1);
			for (int sci = 0; sci < canvasGrids.size(); sci++) {
				CanvasConfig sc = projectData.getCanvases().getCanvases().get(sci);
				Rectangle2D cgb = canvasGrids.get(sci).getBounds2D();
				double sy = cgb.getMinY();
				double sh = cgb.getHeight();
				double stripH = Math.max(sh, 3.0 * rowHeight);
				int colW = trackStripWidth / 3;

				// black fill + vertical rainbow border
				g.setPaint(Color.BLACK);
				g.fillRect(0, (int) sy, trackStripWidth, (int) stripH);
				g.setPaint(new java.awt.LinearGradientPaint(0, (float) sy, 0, (float)(sy + stripH), rbFractions, rbColors));
				// top, bottom, left only — no right edge
				g.drawLine(0, (int) sy,              trackStripWidth - 1, (int) sy);
				g.drawLine(0, (int)(sy + stripH) - 1, trackStripWidth - 1, (int)(sy + stripH) - 1);
				g.drawLine(0, (int) sy,              0,                   (int)(sy + stripH) - 1);

				boolean canUp   = sci > 0;
				boolean canDown = sci < canvasGrids.size() - 1;

				g.setFont(gridFont);
				int ascent  = gridMetrics.getMaxAscent();
				int descent = gridMetrics.getMaxDescent();

				// Click bounds — [0]=up [1]=down [2]=edit [3]=mute [4]=delete [5]=note [6]=vel [7]=pan [8]=offset
				// Disabled arrows use zero-size rect (unclickable)
				Rectangle2D[] sb = new Rectangle2D[9];
				for (int i = 0; i < 9; i++) sb[i] = new Rectangle2D.Double(0, 0, 0, 0);

				// Helper: baseline y for text vertically centered in a cell rect
				// ly = cellTop + (cellHeight + ascent - descent) / 2
				// Col 0: pack enabled items top-down — up (if canUp), down (if canDown), X
				int col0Row = 0;
				if (canUp) {
					Rectangle2D r = new Rectangle2D.Double(0, sy + col0Row * rowHeight, colW, rowHeight);
					sb[0] = r;
					int lw = gridMetrics.stringWidth("\u2191");
					int lx = (int)(r.getMinX() + (r.getWidth() - lw) / 2.0);
					int ly = (int)(r.getMinY() + (rowHeight + ascent - descent) / 2.0);
					g.setPaint(Color.WHITE);
					g.drawString("\u2191", lx, ly);
					col0Row++;
				}
				if (canDown) {
					Rectangle2D r = new Rectangle2D.Double(0, sy + col0Row * rowHeight, colW, rowHeight);
					sb[1] = r;
					int lw = gridMetrics.stringWidth("\u2193");
					int lx = (int)(r.getMinX() + (r.getWidth() - lw) / 2.0);
					int ly = (int)(r.getMinY() + (rowHeight + ascent - descent) / 2.0);
					g.setPaint(Color.WHITE);
					g.drawString("\u2193", lx, ly);
					col0Row++;
				}
				{
					Rectangle2D r = new Rectangle2D.Double(0, sy + col0Row * rowHeight, colW, rowHeight);
					sb[4] = r;
					int lw = gridMetrics.stringWidth("X");
					int lx = (int)(r.getMinX() + (r.getWidth() - lw) / 2.0);
					int ly = (int)(r.getMinY() + (rowHeight + ascent - descent) / 2.0);
					g.setPaint(new Color(220, 80, 80));
					g.drawString("X", lx, ly);
				}

				// Col 1: [m] row 0, [e] row 1, [±t] row 2 (drum/string only)
				{
					String ofsLabel = "[\u00B1t]";
					int col1LabelW = Math.max(gridMetrics.stringWidth("[m]"), Math.max(gridMetrics.stringWidth("[e]"), gridMetrics.stringWidth(ofsLabel)));
					int col1Lx = (int)(colW + (colW - col1LabelW) / 2.0);

					if (!(sc instanceof EventCanvasConfig)) {
						Rectangle2D rM = new Rectangle2D.Double(colW, sy, colW, rowHeight);
						sb[3] = rM;
						int lyM = (int)(rM.getMinY() + (rowHeight + ascent - descent) / 2.0);
						g.setPaint(sc.isMuted() ? new Color(0xBF, 0x57, 0x00) : Color.WHITE);
						g.drawString("[m]", col1Lx, lyM);
					}

					Rectangle2D rE = new Rectangle2D.Double(colW, sy + rowHeight, colW, rowHeight);
					sb[2] = rE;
					int lyE = (int)(rE.getMinY() + (rowHeight + ascent - descent) / 2.0);
					g.setPaint(Color.WHITE);
					g.drawString("[e]", col1Lx, lyE);

					if (sc instanceof StringCanvasConfig || sc instanceof DrumCanvasConfig) {
						Rectangle2D rT = new Rectangle2D.Double(colW, sy + 2*rowHeight, colW, rowHeight);
						sb[8] = rT;
						int lyT = (int)(rT.getMinY() + (rowHeight + ascent - descent) / 2.0);
						boolean offsetActive = sc.getEditMode() == CanvasEditMode.OFFSET;
						g.setPaint(offsetActive ? new Color(0, 220, 220) : new Color(100, 100, 100));
						g.drawString(ofsLabel, col1Lx, lyT);
					}
				}

				// Col 2: [n][v][p] mode buttons — only for drum/string canvases
				if (sc instanceof StringCanvasConfig || sc instanceof DrumCanvasConfig) {
					CanvasEditMode curMode = sc.getEditMode();
					String[] modeLabels = {"[n]", "[v]", "[p]"};
					CanvasEditMode[] modes = {CanvasEditMode.NOTE, CanvasEditMode.VELOCITY, CanvasEditMode.PAN};
					Color[] modeColors = {Color.WHITE, new Color(255, 220, 50), new Color(220, 80, 220)};
					int[] sbIdx = {5, 6, 7};
					int col2LabelW = gridMetrics.stringWidth("[n]");
					for (String lbl : modeLabels) col2LabelW = Math.max(col2LabelW, gridMetrics.stringWidth(lbl));
					int col2Lx = (int)(2*colW + (colW - col2LabelW) / 2.0);
					for (int mi = 0; mi < 3; mi++) {
						Rectangle2D rMode = new Rectangle2D.Double(2*colW, sy + mi * rowHeight, colW, rowHeight);
						sb[sbIdx[mi]] = rMode;
						int lyMode = (int)(rMode.getMinY() + (rowHeight + ascent - descent) / 2.0);
						boolean isActive = curMode == modes[mi];
						g.setPaint(isActive ? modeColors[mi] : new Color(100, 100, 100));
						g.drawString(modeLabels[mi], col2Lx, lyMode);
					}
				}

				lastStripBounds.add(sb);
			}
			// + ADD CANVAS bar below last canvas grid (and its measure panel)
			if (projectData != null) {
				double addY, addH;
				if (!canvasGrids.isEmpty() && !measurePanels.isEmpty()) {
					Rectangle2D lastMeasure = measurePanels.get(measurePanels.size() - 1);
					addY = lastMeasure.getMaxY() + 2;
					addH = lastRowHeight;
				} else {
					// No canvases yet — position below the scrollbar
					addY = lastTopBarHeight + 14;
					addH = lastRowHeight > 0 ? lastRowHeight : 20;
				}
				boolean hasCanvases = !canvasGrids.isEmpty();
				double barW = hasCanvases ? trackStripWidth : W;
				lastAddCanvasBounds = new Rectangle2D.Double(0, addY, barW, addH);

				// black fill
				g.setPaint(Color.BLACK);
				g.fillRect(0, (int) addY, (int) barW, (int) addH);

				// horizontal rainbow border
				g.setStroke(STROKE_1);
				g.setPaint(new java.awt.LinearGradientPaint(0, (float) addY, (float) barW, (float) addY, rbFractions, rbColors));
				g.drawRect(0, (int) addY, (int) barW - 1, (int) addH - 1);

				// bright white '+' as two crossed lines, stroke 2, exact center
				double cx  = barW / 2.0;
				double cy  = addY + addH / 2.0;
				double arm = addH / 3.0;
				g.setStroke(STROKE_2);
				g.setPaint(Color.WHITE);
				g.draw(new Line2D.Double(cx - arm, cy,       cx + arm, cy));
				g.draw(new Line2D.Double(cx,       cy - arm, cx,       cy + arm));
				g.setStroke(STROKE_1);
			}



			// --- hint bar overlay at bottom ---
			Pair<Integer,Integer> hintPair = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			int hintCanvasIdx = hintPair.a;
			int hintRelRow = hintPair.b;
			if (hintCanvasIdx < projectData.getCanvases().getCanvases().size()) {
				CanvasConfig hintCanvas = projectData.getCanvases().getCanvases().get(hintCanvasIdx);
				List<String> hintLines = new java.util.ArrayList<>();

				if (hintCanvas instanceof EventCanvasConfig) {
					hintLines.add("T: time sig   S: tempo   F: shuffle   N: note   P: program   U: tuning   Q: tuplet");
				} else if (hintCanvas instanceof DrumCanvasConfig || hintCanvas instanceof StringCanvasConfig) {
					CanvasEditMode hintMode = hintCanvas.getEditMode();
					if (hintMode == CanvasEditMode.VELOCITY) {
						hintLines.add("[VEL]  0-9 A-F: enter velocity (hex)   Alt\u2191\u2193: nudge   n: note   p: pan   o: offset");
					} else if (hintMode == CanvasEditMode.PAN) {
						hintLines.add("[PAN]  0-9 A-F: enter pan (hex)   Alt\u2191\u2193: nudge   n: note   v: vel   o: offset");
					} else if (hintMode == CanvasEditMode.OFFSET) {
						hintLines.add("[OFFSET]  Alt\u2191\u2193: nudge timing offset   \u2190\u2192: skip to valued cells   n: note   v: vel   p: pan");
					} else if (hintCanvas instanceof DrumCanvasConfig) {
						DrumCanvasConfig dc = (DrumCanvasConfig) hintCanvas;
						if (hintRelRow < dc.getRowTypes().size()) {
							PercRowType rowType = dc.getRowTypes().get(hintRelRow);
							StringBuilder sb = new StringBuilder("[NOTE]  " + rowType.toString().toLowerCase() + ":  ");
							for (PercToken pt : dc.getTokens()) {
								if (pt.getPosition() == rowType) sb.append(pt.getToken()).append("  ");
							}
							sb.append("  v: vel   p: pan   o: offset");
							hintLines.add(sb.toString().trim());
						}
					} else {
						StringCanvasConfig sc = (StringCanvasConfig) hintCanvas;
						StringBuilder sb = new StringBuilder("[NOTE]  0\u2013").append(sc.getMaxFrets());
						if (sc.getMaxHarmonic() > 0) sb.append("   H\u2013H").append(sc.getMaxHarmonic());
						for (String key : sc.getAdditionalPitchMap().keySet()) {
							if (!key.matches("\\d+")) sb.append("   ").append(key);
						}
						sb.append("   v: vel   p: pan   o: offset");
						hintLines.add(sb.toString());
					}
				}

				// second line: global / selection-mode shortcuts
				String mod = IS_MAC ? "\u2318" : "ctrl+";
				String modAlt = IS_MAC ? "\u2318" : "alt+";
				StringBuilder globalHints = new StringBuilder();
				if (isSelectionMode.get()) {
					globalHints.append(mod).append("C: copy   ");
					globalHints.append(mod).append("X: cut   ");
					globalHints.append("esc: cancel");
				} else {
					globalHints.append(mod).append("L: select   ");
					globalHints.append(mod).append("[: rpt start   ");
					globalHints.append(mod).append("]: rpt end   ");
					globalHints.append("space: play   \u21e7space: play\u21ba   ");
					globalHints.append(mod).append("S: save   ");
					globalHints.append(mod).append("E: edit canvas");
				}
				if (!instrumentClipboard.isEmpty() || !eventClipboard.isEmpty()) {
					globalHints.append("   ").append(mod).append("V: paste");
				}
				hintLines.add(globalHints.toString());

				{
					g.setFont(topFont);
					int lineH = topFontMetrics.getMaxAscent() + topFontMetrics.getMaxDescent();
					int sepH = 5; // 2 lines at y+0, y+2 plus 3px gap before text
					int barH = lineH * hintLines.size() + sepH + 2;
					int barY = (int)(H - barH);
					g.setPaint(Color.BLACK);
					g.fill(new Rectangle2D.Double(0, barY, W, barH));
					g.setPaint(getHintRainbow((float) W));
					g.draw(new Line2D.Double(0, barY, W, barY));
					g.draw(new Line2D.Double(0, barY + 2, W, barY + 2));
					g.setPaint(new Color(160, 160, 160));
					for (int hi = 0; hi < hintLines.size(); hi++) {
						int textY = barY + sepH + topFontMetrics.getMaxAscent() + hi * (lineH + 1);
						g.drawString(hintLines.get(hi), 4, textY);
					}
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
	
	class TimeSignatureEventPanel extends JPanel implements InlineEntryPanel {

		boolean flag = false;
		IntSpinner numerator   = new IntSpinner("Numerator",   4, 1, 999);
		IntSpinner denominator = new IntSpinner("Denominator", 4, new int[]{2, 4, 8, 16});

		void up()    { (flag ? denominator : numerator).up(); }
		void down()  { (flag ? denominator : numerator).down(); }
		void left()  { flag = !flag; }
		void right() { flag = !flag; }

		void enter() {
			TimeSignatureDenominator tsd =
					TimeSignatureDenominator.fromInt(denominator.getValue()).get();
			TimeSignatureEvent tse =
					new TimeSignatureEvent(numerator.getValue(), tsd);
			Pair<Integer,Integer> tsPair = mainInterfacePanel.getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			CanvasConfig tsCanvas = projectData.getCanvases().getCanvases().get(tsPair.a);
			if (tsCanvas instanceof EventCanvasConfig) {
				pushUndo();
				projectData.getEventData(tsCanvas.getId()).put(
						new Point(projectData.getCursorT().get(), tsPair.b),
						tse);
			}
			numerator.reset();
			denominator.reset();
			updateMeasureLinePositions();
			exitInlineMode();
		}

		public void inlineUp()    { up(); }
		public void inlineDown()  { down(); }
		public void inlineLeft()  { left(); }
		public void inlineRight() { right(); }
		public void inlineEnter() { enter(); }
		public void paintInline(Graphics2D g, int x, int y, int w, int h) {
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fillRect(x, y, w, h);
			int drawY = y + textFontMetrics.getMaxAscent() * 2;
			int x2 = numerator.paint(g, x + 2, drawY, !flag);
			denominator.paint(g, x2 + 20, drawY, flag);
		}

	}

	class TempoEventPanel extends JPanel implements InlineEntryPanel {
		IntSpinner tempo = new IntSpinner("TEMPO", 120, 20, 1000);

		void enter() {
			TempoEvent tempoEvent = new TempoEvent(tempo.getValue());
			tempo.reset();
			Pair<Integer,Integer> tempoPair = mainInterfacePanel.getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			CanvasConfig tempoCanvas = projectData.getCanvases().getCanvases().get(tempoPair.a);
			if (tempoCanvas instanceof EventCanvasConfig) {
				pushUndo();
				projectData.getEventData(tempoCanvas.getId()).put(
						new Point(projectData.getCursorT().get(), tempoPair.b),
						tempoEvent);
			}
			exitInlineMode();
		}
		void up()   { tempo.up(); }
		void down() { tempo.down(); }

		public void inlineUp()    { up(); }
		public void inlineDown()  { down(); }
		public void inlineLeft()  {}
		public void inlineRight() {}
		public void inlineEnter() { enter(); }
		public void paintInline(Graphics2D g, int x, int y, int w, int h) {
			g.setPaint(Color.BLACK);
			g.fillRect(x, y, w, h);
			g.setFont(textFont);
			tempo.paint(g, x + 2, y + textFontMetrics.getMaxAscent(), true);
		}

	}

	class ShuffleEventPanel extends JPanel implements InlineEntryPanel {
		IntSpinner shuffle = new IntSpinner("SHUFFLE", 0, -90, 90);

		void enter() {
			ShuffleEvent event = new ShuffleEvent(shuffle.getValue());
			shuffle.reset();
			Pair<Integer,Integer> shufflePair = mainInterfacePanel.getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			CanvasConfig shuffleCanvas = projectData.getCanvases().getCanvases().get(shufflePair.a);
			if (shuffleCanvas instanceof EventCanvasConfig) {
				pushUndo();
				projectData.getEventData(shuffleCanvas.getId()).put(
					new Point(projectData.getCursorT().get(), shufflePair.b),
					event);
			}
			exitInlineMode();
		}
		void up()   { shuffle.up(); }
		void down() { shuffle.down(); }

		public void inlineUp()    { up(); }
		public void inlineDown()  { down(); }
		public void inlineLeft()  {}
		public void inlineRight() {}
		public void inlineEnter() { enter(); }
		public void paintInline(Graphics2D g, int x, int y, int w, int h) {
			g.setPaint(Color.BLACK);
			g.fillRect(x, y, w, h);
			g.setFont(textFont);
			shuffle.paint(g, x + 2, y + textFontMetrics.getMaxAscent(), true);
		}

	}

	class ProgramChangeEventPanel extends JPanel implements InlineEntryPanel {
		int focus = 0; // 0 = canvas, 1 = bank, 2 = program
		List<String> canvasOptions = new ArrayList<>();
		int canvasIndex = 0;
		IntSpinner bank    = new IntSpinner("BANK",    0, 0, 16383);
		IntSpinner program = new IntSpinner("PROGRAM", 0, 0, 127);

		void refresh() {
			canvasOptions.clear();
			if (projectData != null) {
				for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
					if (!(c instanceof EventCanvasConfig)) {
						canvasOptions.add(c.getName());
					}
				}
			}
			Pair<Integer,Integer> refreshPair = mainInterfacePanel.getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			CanvasConfig refreshCanvas = projectData.getCanvases().getCanvases().get(refreshPair.a);
			Point p = new Point(projectData.getCursorT().get(), refreshPair.b);
			ControlEvent existing = (refreshCanvas instanceof EventCanvasConfig)
					? projectData.getEventData(refreshCanvas.getId()).get(p) : null;
			if (existing != null && existing.getType() == ControlEventType.PROGRAM_CHANGE) {
				ProgramChange pc = (ProgramChange) existing;
				int idx = canvasOptions.indexOf(pc.getInstrument());
				canvasIndex = (idx >= 0) ? idx : 0;
				bank.setValue(pc.getBank());
				program.setValue(pc.getProgram());
			} else {
				canvasIndex = 0;
				bank.reset();
				program.reset();
			}
			focus = 0;
		}

		void left()  { focus = (focus + 2) % 3; }
		void right() { focus = (focus + 1) % 3; }

		void up() {
			if (focus == 0) canvasIndex = Math.min(canvasIndex + 1, canvasOptions.size() - 1);
			else if (focus == 1) bank.up();
			else program.up();
		}

		void down() {
			if (focus == 0) canvasIndex = Math.max(canvasIndex - 1, 0);
			else if (focus == 1) bank.down();
			else program.down();
		}

		void enter() {
			String name = canvasOptions.isEmpty() ? "" : canvasOptions.get(canvasIndex);
			ProgramChange pc = new ProgramChange(name, bank.getValue(), program.getValue());
			Pair<Integer,Integer> pcPair = mainInterfacePanel.getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			CanvasConfig pcCanvas = projectData.getCanvases().getCanvases().get(pcPair.a);
			if (pcCanvas instanceof EventCanvasConfig) {
				pushUndo();
				projectData.getEventData(pcCanvas.getId()).put(
					new Point(projectData.getCursorT().get(), pcPair.b), pc);
			}
			exitInlineMode();
		}

		public void inlineUp()    { up(); }
		public void inlineDown()  { down(); }
		public void inlineLeft()  { left(); }
		public void inlineRight() { right(); }
		public void inlineEnter() { enter(); }
		public void paintInline(Graphics2D g, int x, int y, int w, int h) {
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fillRect(x, y, w, h);
			int drawY = y + textFontMetrics.getMaxAscent() * 2;
			int dx = x + 2;
			String canvasDisplay = canvasOptions.isEmpty() ? "(none)" : canvasOptions.get(canvasIndex);
			String canvasLabel = "CANVAS: " + canvasDisplay;
			g.setPaint(focus == 0 ? Color.WHITE : Color.GRAY);
			g.drawString(canvasLabel, dx, drawY);
			dx += textFontMetrics.stringWidth(canvasLabel) + 20;
			dx = bank.paint(g, dx, drawY, focus == 1);
			dx += 20;
			program.paint(g, dx, drawY, focus == 2);
		}

	}

	class TuningChangeEventPanel extends JPanel implements InlineEntryPanel {
		int focus = 0; // 0 = canvas, 1 = row, 2 = offset
		List<String> canvasOptions = new ArrayList<>();
		int canvasIndex = 0;
		int rowIndex = 0;
		IntSpinner offset = new IntSpinner("OFFSET", 0, -127, 127);

		void refresh() {
			canvasOptions.clear();
			if (projectData != null) {
				for (CanvasConfig c : projectData.getCanvases().getCanvases()) {
					if (c instanceof StringCanvasConfig) {
						canvasOptions.add(c.getName());
					}
				}
			}
			Pair<Integer,Integer> pair = mainInterfacePanel.getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			CanvasConfig refreshCanvas = projectData.getCanvases().getCanvases().get(pair.a);
			Point p = new Point(projectData.getCursorT().get(), pair.b);
			ControlEvent existing = (refreshCanvas instanceof EventCanvasConfig)
					? projectData.getEventData(refreshCanvas.getId()).get(p) : null;
			if (existing != null && existing.getType() == ControlEventType.TUNING_CHANGE) {
				TuningChangeEvent tc = (TuningChangeEvent) existing;
				int idx = canvasOptions.indexOf(tc.getCanvasName());
				canvasIndex = (idx >= 0) ? idx : 0;
				rowIndex = tc.getRow();
				offset.setValue((int) tc.getStepOffset());
			} else {
				canvasIndex = 0;
				rowIndex = 0;
				offset.setValue(0);
			}
			focus = 0;
		}

		void left()  { focus = (focus + 2) % 3; }
		void right() { focus = (focus + 1) % 3; }

		void up() {
			if (focus == 0) canvasIndex = Math.min(canvasIndex + 1, canvasOptions.size() - 1);
			else if (focus == 1) {
				String name = canvasOptions.isEmpty() ? null : canvasOptions.get(canvasIndex);
				int maxRow = 0;
				if (name != null) {
					java.util.Optional<CanvasConfig> cc = projectData.getCanvasConfig(name);
					if (cc.isPresent()) maxRow = cc.get().getRowCount() - 1;
				}
				rowIndex = Math.min(rowIndex + 1, maxRow);
			} else offset.up();
		}

		void down() {
			if (focus == 0) canvasIndex = Math.max(canvasIndex - 1, 0);
			else if (focus == 1) rowIndex = Math.max(rowIndex - 1, 0);
			else offset.down();
		}

		void enter() {
			String name = canvasOptions.isEmpty() ? "" : canvasOptions.get(canvasIndex);
			TuningChangeEvent tc = new TuningChangeEvent(name, rowIndex, offset.getValue());
			Pair<Integer,Integer> tcPair = mainInterfacePanel.getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
			CanvasConfig tcCanvas = projectData.getCanvases().getCanvases().get(tcPair.a);
			if (tcCanvas instanceof EventCanvasConfig) {
				pushUndo();
				projectData.getEventData(tcCanvas.getId()).put(
					new Point(projectData.getCursorT().get(), tcPair.b), tc);
			}
			exitInlineMode();
		}

		public void inlineUp()    { up(); }
		public void inlineDown()  { down(); }
		public void inlineLeft()  { left(); }
		public void inlineRight() { right(); }
		public void inlineEnter() { enter(); }
		public void paintInline(Graphics2D g, int x, int y, int w, int h) {
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fillRect(x, y, w, h);
			int drawY = y + textFontMetrics.getMaxAscent() * 2;
			int dx = x + 2;
			String canvasDisplay = canvasOptions.isEmpty() ? "(none)" : canvasOptions.get(canvasIndex);
			String canvasLabel = "CANVAS: " + canvasDisplay;
			g.setPaint(focus == 0 ? Color.WHITE : Color.GRAY);
			g.drawString(canvasLabel, dx, drawY);
			dx += textFontMetrics.stringWidth(canvasLabel) + 20;
			String rowLabel = "ROW: " + rowIndex;
			g.setPaint(focus == 1 ? Color.WHITE : Color.GRAY);
			g.drawString(rowLabel, dx, drawY);
			dx += textFontMetrics.stringWidth(rowLabel) + 20;
			dx = offset.paint(g, dx, drawY, focus == 2);
			dx += 20;
			String tcName = canvasOptions.isEmpty() ? null : canvasOptions.get(canvasIndex);
			if (tcName != null) {
				java.util.Optional<CanvasConfig> cc = projectData.getCanvasConfig(tcName);
				if (cc.isPresent() && cc.get() instanceof StringCanvasConfig) {
					StringCanvasConfig sc = (StringCanvasConfig) cc.get();
					if (rowIndex < sc.getEdoSteps().length) {
						String baseLabel = "BASE: " + sc.getEdoSteps()[rowIndex];
						g.setPaint(new Color(150, 150, 150));
						g.drawString(baseLabel, dx, drawY);
					}
				}
			}
		}

	}

	class TupletEventPanel extends JPanel implements InlineEntryPanel {
		IntSpinner subdivisions = new IntSpinner("SUBDIVISIONS", 3, 1, Integer.MAX_VALUE);

		void up()   { subdivisions.up(); }
		void down() { subdivisions.down(); }

		void enter() {
			if (projectData == null) return;
			int t0 = mainInterfacePanel.pendingTupletT0;
			int t1 = mainInterfacePanel.pendingTupletT1;
			String canvasName = mainInterfacePanel.tupletSelectionCanvasId;
			int row = mainInterfacePanel.tupletSelectionRow;
			if (t0 < 0 || t1 <= t0 || canvasName == null) {
				exitInlineMode();
				return;
			}
			int B = subdivisions.getValue();
			// For B > A: check extended storage range [t1, t0+B) doesn't overlap existing tuplets
			int storageEnd = Math.max(t1, t0 + B);
			if (storageEnd > t1) {
				java.util.NavigableMap<Integer, TupletEvent> ext = cachedTuplets.subMap(t1, true, storageEnd, false);
				if (!ext.isEmpty()) { mainInterfacePanel.repaint(); return; }
				// Also check the preceding tuplet doesn't extend into [t0, storageEnd)
				java.util.NavigableMap<Integer, TupletEvent> before = cachedTuplets.headMap(t0, false);
				if (!before.isEmpty()) {
					Map.Entry<Integer, TupletEvent> prev = before.lastEntry();
					int prevA = prev.getValue().getEndT() - prev.getKey();
					int prevB = prev.getValue().getSubdivisions();
					if (prev.getKey() + Math.max(prevA, prevB) > t0) { mainInterfacePanel.repaint(); return; }
				}
			}
			pushUndo();
			TupletEvent te = new TupletEvent(B, t1);
			projectData.getEventData(canvasName).put(new Point(t0, row), te);
			updateTupletCache();
			updateMeasureLinePositions();
			exitInlineMode();
		}

		public void inlineUp()    { up(); }
		public void inlineDown()  { down(); }
		public void inlineLeft()  {}
		public void inlineRight() {}
		public void inlineEnter() { enter(); }
		public void paintInline(Graphics2D g, int x, int y, int w, int h) {
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fillRect(x, y, w, h);
			g.setPaint(Color.WHITE);
			int lineH = textFontMetrics.getMaxAscent();
			int drawY = y + lineH + 4;
			int totalTicks = mainInterfacePanel.pendingTupletT1 - mainInterfacePanel.pendingTupletT0;
			int subdiv = subdivisions.getValue();
			int gcd = subdiv; int rem = totalTicks; while (rem != 0) { int tmp = rem; rem = gcd % rem; gcd = tmp; }
			String reduced = (gcd > 1) ? "  (" + (subdiv / gcd) + ":" + (totalTicks / gcd) + ")" : "";
			g.drawString("TUPLET  [" + subdiv + " | " + totalTicks + " ticks]" + reduced, x + 4, drawY);
			drawY += lineH + 8;
			subdivisions.paint(g, x + 4, drawY, true);
		}

	}

	class SettingsPanel extends JPanel {
		int settingsFocus = 0; // 0=Title 1=Artist 2=Edit UI Settings 3=Output Device
		private StringBuffer songBuffer   = new StringBuffer();
		private StringBuffer artistBuffer = new StringBuffer();

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
			inputMap.put(k_Backspace, "s_bsp");
			actionMap.put("s_bsp", rToA(this::settingsBsp));
			inputMap.put(k_Space, "s_spc");
			actionMap.put("s_spc", rToA(() -> settingsAppend(' ')));
			for (char c = 'A'; c <= 'Z'; c++) {
				char lo = Character.toLowerCase(c), up = c;
				char c_ = c;
				inputMap.put(KeyStroke.getKeyStroke(up + ""),        "s_l" + lo);
				inputMap.put(KeyStroke.getKeyStroke("shift " + up),  "s_u" + lo);
				actionMap.put("s_l" + lo, rToA(() -> settingsAppend(lo)));
				actionMap.put("s_u" + lo, rToA(() -> settingsAppend(c_)));
			}
			for (char c = '0'; c <= '9'; c++) {
				char c_ = c;
				inputMap.put(KeyStroke.getKeyStroke("" + c), "s_d" + c);
				actionMap.put("s_d" + c, rToA(() -> settingsAppend(c_)));
			}
			for (char c : new char[]{'.', ',', '!', '?', '\'', '-', '_'}) {
				char c_ = c;
				inputMap.put(KeyStroke.getKeyStroke(c), "s_p" + c);
				actionMap.put("s_p" + c, rToA(() -> settingsAppend(c_)));
			}
		}

		void refresh() {
			if (projectData != null) {
				songBuffer.setLength(0);   songBuffer.append(projectData.getSongName());
				artistBuffer.setLength(0); artistBuffer.append(projectData.getArtistName());
			}
			repaint();
		}

		void up() {
			settingsFocus--;
			if (settingsFocus < 0) settingsFocus = 3;
			if (projectData == null && settingsFocus < 2) settingsFocus = 3;
			repaint();
		}

		void down() {
			settingsFocus++;
			if (settingsFocus > 3) settingsFocus = (projectData != null) ? 0 : 2;
			repaint();
		}

		void adjustLeft()  { }
		void adjustRight() { }

		void enter() {
			if (settingsFocus == 2) {
				uiSettingsPanel.refresh();
				cardLayout.show(cardPanel, uiSettingsCardKey);
			} else if (settingsFocus == 3) {
				audioOutputPanel.refresh();
				cardLayout.show(cardPanel, audioOutputCardKey);
			}
		}

		void settingsAppend(char c) {
			if (projectData == null) return;
			if (settingsFocus == 0) { songBuffer.append(c); projectData.setSongName(songBuffer.toString()); repaint(); }
			else if (settingsFocus == 1) { artistBuffer.append(c); projectData.setArtistName(artistBuffer.toString()); repaint(); }
		}

		void settingsBsp() {
			if (projectData == null) return;
			if (settingsFocus == 0 && songBuffer.length() > 0) { songBuffer.deleteCharAt(songBuffer.length()-1); projectData.setSongName(songBuffer.toString()); repaint(); }
			else if (settingsFocus == 1 && artistBuffer.length() > 0) { artistBuffer.deleteCharAt(artistBuffer.length()-1); projectData.setArtistName(artistBuffer.toString()); repaint(); }
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.BLACK);
			g.fill(getBounds());
			g.setFont(textFont);

			int lineH = textFontMetrics.getHeight();
			int x = 10;
			int y = lineH;

			// --- Title row ---
			if (projectData != null) {
				boolean sf = settingsFocus == 0;
				String slabel = "Title:";
				int slabelW = textFontMetrics.stringWidth(slabel);
				g.setPaint(sf ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(slabel, x, y);
				g.setPaint(sf ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + songBuffer + (sf ? "|" : ""), x + slabelW, y);
				y += lineH + 4;

				// --- Artist row ---
				boolean af = settingsFocus == 1;
				String alabel = "Artist:";
				int alabelW = textFontMetrics.stringWidth(alabel);
				g.setPaint(af ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(alabel, x, y);
				g.setPaint(af ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + artistBuffer + (af ? "|" : ""), x + alabelW, y);
				y += lineH + 8;
			}

			// --- Edit UI Settings row ---
			boolean uiFocused = settingsFocus == 2;
			g.setPaint(uiFocused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString("Edit UI Settings", x, y);
			g.setPaint(uiFocused ? new Color(255, 255, 100) : Color.GRAY);
			g.drawString(" >", x + textFontMetrics.stringWidth("Edit UI Settings"), y);

			// --- Output Device row ---
			y += lineH + 8;
			boolean audioFocused = settingsFocus == 3;
			String deviceLabel = "Output Device:";
			String deviceName = (selectedAudioMixerInfo == null) ? "Default (system)" : selectedAudioMixerInfo.getName();
			int deviceLabelW = textFontMetrics.stringWidth(deviceLabel);
			g.setPaint(audioFocused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString(deviceLabel, x, y);
			g.setPaint(audioFocused ? new Color(255, 255, 100) : Color.GRAY);
			g.drawString(" " + deviceName, x + deviceLabelW, y);

			// --- Nav hint ---
			y += lineH * 2;
			g.setPaint(new Color(70, 70, 70));
			if (settingsFocus == 0 || settingsFocus == 1) {
				g.drawString("UP/DOWN: navigate   A-Z/0-9: type   Backspace: delete   ESC: back", x, y);
			} else {
				g.drawString("UP/DOWN: navigate   ENTER: open   ESC: back", x, y);
			}
		}
	}

	class UISettingsPanel extends JPanel {
		FloatSpinner uiScale = new FloatSpinner("UI Scale", UI_SCALE, 0.1, 10.0, 0.1);
		IntSpinner renderFpsSpinner = new IntSpinner("Render FPS", 50, 10, 120);
		FloatSpinner rainbowBrightnessSpinner = new FloatSpinner("Rainbow Brightness", 1.0, 0.1, 1.0, 0.1);
		FloatSpinner scrollbarBrightnessSpinner = new FloatSpinner("Scrollbar Brightness", 1.0, 0.1, 1.0, 0.1);
		private String[] availableFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
		private int fontFamilyIndex = 0;
		private IntSpinner gridFontSizeSpinner = new IntSpinner("Grid Font Size", 12, 6, 48);
		private int textFontFamilyIndex = 0;
		private IntSpinner textFontSizeSpinner = new IntSpinner("UI Font Size", 14, 6, 48);
		int uiFocus = 0; // 0=UI Scale 1=Render FPS 2=Rainbow Brightness 3=Scrollbar Brightness 4=Grid Font Family 5=Grid Font Size 6=UI Font Family 7=UI Font Size

		public UISettingsPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Escape, "esc");
			actionMap.put("esc", rToA(() -> { cardLayout.show(cardPanel, settingsCardKey); }));
			inputMap.put(k_Up, "up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down, "down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Left, "left");
			actionMap.put("left", rToA(this::adjustLeft));
			inputMap.put(k_Right, "right");
			actionMap.put("right", rToA(this::adjustRight));
		}

		void refresh() {
			uiScale.setValue(displayScale);
			renderFpsSpinner.setValue(1000 / playbackRenderIntervalMs);
			rainbowBrightnessSpinner.setValue(rainbowBrightness);
			scrollbarBrightnessSpinner.setValue(scrollbarBrightness);
			gridFontSizeSpinner.setValue(gridFontSize);
			textFontSizeSpinner.setValue(textFontSize);
			fontFamilyIndex = 0;
			textFontFamilyIndex = 0;
			for (int i = 0; i < availableFonts.length; i++) {
				if (availableFonts[i].equals(gridFontFamily)) fontFamilyIndex = i;
				if (availableFonts[i].equals(textFontFamily)) textFontFamilyIndex = i;
			}
			repaint();
		}

		void up() {
			uiFocus = (uiFocus - 1 + 8) % 8;
			repaint();
		}

		void down() {
			uiFocus = (uiFocus + 1) % 8;
			repaint();
		}

		void adjustLeft() {
			switch (uiFocus) {
				case 0: uiScale.left(); displayScale = uiScale.getValue();
						if (projectData != null) projectData.setUiScale(uiScale.getValue()); break;
				case 1: renderFpsSpinner.left(); playbackRenderIntervalMs = 1000 / renderFpsSpinner.getValue();
						if (playbackRenderTimer != null) playbackRenderTimer.setDelay(playbackRenderIntervalMs); break;
				case 2: rainbowBrightnessSpinner.left(); rainbowBrightness = rainbowBrightnessSpinner.getValue();
						rbColors = buildRbColors(); cachedRainbowPaint = null;
						if (projectData != null) projectData.setRainbowBrightness(rainbowBrightness);
						mainInterfacePanel.repaint(); break;
				case 3: scrollbarBrightnessSpinner.left(); scrollbarBrightness = scrollbarBrightnessSpinner.getValue();
						scrollbarRainbowImage = buildScrollbarRainbow();
						if (projectData != null) projectData.setScrollbarBrightness(scrollbarBrightness);
						mainInterfacePanel.repaint(); break;
				case 4: fontFamilyIndex = (fontFamilyIndex - 1 + availableFonts.length) % availableFonts.length;
						gridFontFamily = availableFonts[fontFamilyIndex];
						if (projectData != null) projectData.setGridFontFamily(gridFontFamily);
						rebuildGridFont(); break;
				case 5: gridFontSizeSpinner.left(); gridFontSize = gridFontSizeSpinner.getValue();
						if (projectData != null) projectData.setGridFontSize(gridFontSize);
						rebuildGridFont(); break;
				case 6: textFontFamilyIndex = (textFontFamilyIndex - 1 + availableFonts.length) % availableFonts.length;
						textFontFamily = availableFonts[textFontFamilyIndex];
						if (projectData != null) projectData.setTextFontFamily(textFontFamily);
						rebuildTextFont(); break;
				case 7: textFontSizeSpinner.left(); textFontSize = textFontSizeSpinner.getValue();
						if (projectData != null) projectData.setTextFontSize(textFontSize);
						rebuildTextFont(); break;
			}
			repaint();
		}

		void adjustRight() {
			switch (uiFocus) {
				case 0: uiScale.right(); displayScale = uiScale.getValue();
						if (projectData != null) projectData.setUiScale(uiScale.getValue()); break;
				case 1: renderFpsSpinner.right(); playbackRenderIntervalMs = 1000 / renderFpsSpinner.getValue();
						if (playbackRenderTimer != null) playbackRenderTimer.setDelay(playbackRenderIntervalMs); break;
				case 2: rainbowBrightnessSpinner.right(); rainbowBrightness = rainbowBrightnessSpinner.getValue();
						rbColors = buildRbColors(); cachedRainbowPaint = null;
						if (projectData != null) projectData.setRainbowBrightness(rainbowBrightness);
						mainInterfacePanel.repaint(); break;
				case 3: scrollbarBrightnessSpinner.right(); scrollbarBrightness = scrollbarBrightnessSpinner.getValue();
						scrollbarRainbowImage = buildScrollbarRainbow();
						if (projectData != null) projectData.setScrollbarBrightness(scrollbarBrightness);
						mainInterfacePanel.repaint(); break;
				case 4: fontFamilyIndex = (fontFamilyIndex + 1) % availableFonts.length;
						gridFontFamily = availableFonts[fontFamilyIndex];
						if (projectData != null) projectData.setGridFontFamily(gridFontFamily);
						rebuildGridFont(); break;
				case 5: gridFontSizeSpinner.right(); gridFontSize = gridFontSizeSpinner.getValue();
						if (projectData != null) projectData.setGridFontSize(gridFontSize);
						rebuildGridFont(); break;
				case 6: textFontFamilyIndex = (textFontFamilyIndex + 1) % availableFonts.length;
						textFontFamily = availableFonts[textFontFamilyIndex];
						if (projectData != null) projectData.setTextFontFamily(textFontFamily);
						rebuildTextFont(); break;
				case 7: textFontSizeSpinner.right(); textFontSize = textFontSizeSpinner.getValue();
						if (projectData != null) projectData.setTextFontSize(textFontSize);
						rebuildTextFont(); break;
			}
			repaint();
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.BLACK);
			g.fill(getBounds());
			g.setFont(textFont);
			int lineH = textFontMetrics.getHeight();
			int x = 10;
			int y = lineH;

			uiScale.paint(g, x, y, uiFocus == 0);
			y += lineH + 8;
			renderFpsSpinner.paint(g, x, y, uiFocus == 1);
			y += lineH + 8;
			rainbowBrightnessSpinner.paint(g, x, y, uiFocus == 2);
			y += lineH + 8;
			scrollbarBrightnessSpinner.paint(g, x, y, uiFocus == 3);
			y += lineH + 8;
			{
				boolean focused = uiFocus == 4;
				String lbl = "Grid Font:";
				int lblW = textFontMetrics.stringWidth(lbl);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(lbl, x, y);
				g.setPaint(focused ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + gridFontFamily, x + lblW, y);
			}
			y += lineH + 8;
			gridFontSizeSpinner.paint(g, x, y, uiFocus == 5);
			y += lineH + 8;
			{
				boolean focused = uiFocus == 6;
				String lbl = "UI Font:";
				int lblW = textFontMetrics.stringWidth(lbl);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(lbl, x, y);
				g.setPaint(focused ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + textFontFamily, x + lblW, y);
			}
			y += lineH + 8;
			textFontSizeSpinner.paint(g, x, y, uiFocus == 7);

			y += lineH * 2;
			g.setPaint(new Color(70, 70, 70));
			g.drawString("UP/DOWN: navigate   LEFT/RIGHT: adjust   ESC: back", x, y);
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

			double uiScaleValue = displayScale;
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
		int menuFocus = 0; // 0 = Soundfont File, 1 = Bank, 2 = Instrument, 3 = EDO, ...
		int selectedSfIndex = 0; // 0 = Default, 1..sf2Files.size() = sf2Files entries
		private final StringBuffer nameBuffer = new StringBuffer();
		private final StringBuffer edoBuffer = new StringBuffer();
		private final StringBuffer freqBuffer = new StringBuffer();

		private int sfChoiceCount() { return sf2Files.size() + 1; }
		private File sfChoiceFile(int idx) { return idx == 0 ? null : sf2Files.get(idx - 1); }
		private String sfChoiceName(int idx) {
			return idx == 0 ? "Default" : sf2Files.get(idx - 1).getName();
		}
		private int sfIndexForCanvas(StringCanvasConfig canvas) {
			if (!canvas.getSoundfontFile().isPresent()) return 0;
			File current = canvas.getSoundfontFile().get();
			for (int i = 0; i < sf2Files.size(); i++) {
				if (sf2Files.get(i).getName().equals(current.getName())) return i + 1;
			}
			return 0;
		}

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
			inputMap.put(k_Backspace, "is_backspace");
			actionMap.put("is_backspace", rToA(this::edoBackspace));
			for (char c = '0'; c <= '9'; c++) {
				char c_ = c;
				inputMap.put(KeyStroke.getKeyStroke("" + c), "is_" + c);
				actionMap.put("is_" + c, rToA(() -> edoInput(c_)));
			}
			inputMap.put(KeyStroke.getKeyStroke("PERIOD"), "is_dot");
			actionMap.put("is_dot", rToA(() -> edoInput('.')));
			for (char c = 'A'; c <= 'Z'; c++) {
				char lo = Character.toLowerCase(c), up = c;
				char c_ = c;
				inputMap.put(KeyStroke.getKeyStroke(up + ""),          "is_l" + lo);
				inputMap.put(KeyStroke.getKeyStroke("shift " + up),    "is_u" + lo);
				actionMap.put("is_l" + lo, rToA(() -> edoInput(lo)));
				actionMap.put("is_u" + lo, rToA(() -> edoInput(c_)));
			}
			inputMap.put(k_Space, "is_space");
			actionMap.put("is_space", rToA(TabbyCat.this::togglePlayStatus));
			inputMap.put(k_ShiftSpace, "is_shiftspace");
			actionMap.put("is_shiftspace", rToA(TabbyCat.this::playFromStart));
		}

		private void edoInput(char c) {
			if (menuFocus == 0) { nameBuffer.append(c); repaint(); }
			else if (menuFocus == 4) { edoBuffer.append(c); repaint(); }
			else if (menuFocus == 8) { freqBuffer.append(c); repaint(); }
		}

		private void edoBackspace() {
			if (menuFocus == 0 && nameBuffer.length() > 0) {
				nameBuffer.deleteCharAt(nameBuffer.length() - 1); repaint();
			} else if (menuFocus == 4 && edoBuffer.length() > 0) {
				edoBuffer.deleteCharAt(edoBuffer.length() - 1); repaint();
			} else if (menuFocus == 8 && freqBuffer.length() > 0) {
				freqBuffer.deleteCharAt(freqBuffer.length() - 1); repaint();
			}
		}

		private boolean edoBufferValid() {
			try { return Double.parseDouble(edoBuffer.toString()) > 0; }
			catch (NumberFormatException e) { return false; }
		}

		private boolean freqBufferValid() {
			try { return Double.parseDouble(freqBuffer.toString()) > 0; }
			catch (NumberFormatException e) { return false; }
		}

		private void commitEdo() {
			if (targetCanvas == null) return;
			if (edoBufferValid()) targetCanvas.setEd2(Double.parseDouble(edoBuffer.toString()));
			edoBuffer.setLength(0);
			edoBuffer.append(fmtFloat(targetCanvas.getEd2()));
		}

		private void commitFreq() {
			if (targetCanvas == null) return;
			if (freqBufferValid()) targetCanvas.setBaseFrequency(Double.parseDouble(freqBuffer.toString()));
			freqBuffer.setLength(0);
			freqBuffer.append(fmtFloat(targetCanvas.getBaseFrequency()));
		}

		private boolean nameBufferValid() { return !nameBuffer.toString().isEmpty(); }

		private void commitName() {
			if (targetCanvas == null) return;
			String newName = nameBuffer.toString();
			if (!nameBufferValid()) { nameBuffer.setLength(0); nameBuffer.append(targetCanvas.getName()); return; }
		if (!newName.equals(targetCanvas.getName())) {
				targetCanvas.setName(newName);
			}
		}

		void prepare(StringCanvasConfig canvas) {
			targetCanvas = canvas;
			menuFocus = 0;
			selectedSfIndex = sfIndexForCanvas(canvas);
			nameBuffer.setLength(0);
			nameBuffer.append(canvas.getName());
			edoBuffer.setLength(0);
			edoBuffer.append(fmtFloat(canvas.getEd2()));
			freqBuffer.setLength(0);
			freqBuffer.append(fmtFloat(canvas.getBaseFrequency()));
			// eagerly cache soundbank so instrument name shows without needing playback first
			canvas.getSoundfontFile().ifPresent(f -> {
				if (!loadedSoundbanks.containsKey(f)) {
					try { loadedSoundbanks.put(f, MidiSystem.getSoundbank(f)); }
					catch (Exception ignored) {}
				}
			});
		}

		private Soundbank getSoundbankForCanvas(CanvasConfig config) {
			if (config.getSoundfontFile().isPresent()) {
				return loadedSoundbanks.get(config.getSoundfontFile().get());
			}
			for (Map.Entry<Pair<StringCanvasConfig,Integer>,Synthesizer> e : stringSynths.entrySet()) {
				if (e.getKey().a == config) return e.getValue().getDefaultSoundbank();
			}
			return null;
		}

		private String getInstrumentNameAt(Soundbank sb, int bank, int program) {
			if (sb == null) return "";
			for (Instrument inst : sb.getInstruments()) {
				if (inst.getPatch().getBank() == bank && inst.getPatch().getProgram() == program)
					return inst.getName();
			}
			return "";
		}

		private String getInstrumentName(CanvasConfig config) {
			Soundbank sb = getSoundbankForCanvas(config);
			if (sb == null && config.getSoundfontFile().isPresent()) return "";
			return getInstrumentNameAt(sb, config.getBank(), config.getProgram());
		}

		// Step to next program (in direction delta) that has a non-empty name. Stays put if none found.
		private int nextProgram(int bank, int fromProgram, int delta) {
			Soundbank sb = getSoundbankForCanvas(targetCanvas);
			for (int p = fromProgram + delta; p >= 0 && p <= 127; p += delta) {
				if (!getInstrumentNameAt(sb, bank, p).isEmpty()) return p;
			}
			return fromProgram;
		}

		// Step to next bank (in direction delta) that has at least one named instrument. Stays put if none found.
		private int nextBank(int fromBank, int delta) {
			Soundbank sb = getSoundbankForCanvas(targetCanvas);
			if (sb == null) return Math.max(0, fromBank + delta);
			Set<Integer> validBanks = new TreeSet<>();
			for (Instrument inst : sb.getInstruments()) {
				if (!inst.getName().isEmpty()) validBanks.add(inst.getPatch().getBank());
			}
			for (int b = fromBank + delta; delta < 0 ? b >= 0 : b <= 16383; b += delta) {
				if (validBanks.contains(b)) return b;
			}
			return fromBank;
		}

		void esc() {
			commitName();
			commitEdo();
			commitFreq();
			if (targetCanvas != null && !isPlaying.get()) resetSynths();
			cardLayout.show(cardPanel, mainInterfaceCardKey);
		}

		void up() {
			if (menuFocus == 4) commitEdo();
			if (menuFocus == 8) commitFreq();
			menuFocus = Math.max(0, menuFocus - 1);
			repaint();
		}

		void down() {
			if (menuFocus == 0) commitName();
			if (menuFocus == 4) commitEdo();
			if (menuFocus == 8) commitFreq();
			menuFocus = Math.min(10, menuFocus + 1);
			repaint();
		}

		void left() {
			if (targetCanvas == null) return;
			if (menuFocus == 1) {
				selectedSfIndex = Math.max(0, selectedSfIndex - 1);
				repaint();
			} else if (menuFocus == 2) {
				targetCanvas.setBank(nextBank(targetCanvas.getBank(), -1));
				playPreview();
				repaint();
			} else if (menuFocus == 3) {
				targetCanvas.setProgram(nextProgram(targetCanvas.getBank(), targetCanvas.getProgram(), -1));
				playPreview();
				repaint();
			} else if (menuFocus == 5) {
				targetCanvas.setFretStepSkip(targetCanvas.getFretStepSkip() - 1);
				repaint();
			} else if (menuFocus == 6) {
				targetCanvas.setMaxFrets(targetCanvas.getMaxFrets() - 1);
				repaint();
			} else if (menuFocus == 7) {
				targetCanvas.setMaxHarmonic(targetCanvas.getMaxHarmonic() - 1);
				repaint();
			}
		}

		void right() {
			if (targetCanvas == null) return;
			if (menuFocus == 1) {
				selectedSfIndex = Math.min(sfChoiceCount() - 1, selectedSfIndex + 1);
				repaint();
			} else if (menuFocus == 2) {
				targetCanvas.setBank(nextBank(targetCanvas.getBank(), 1));
				playPreview();
				repaint();
			} else if (menuFocus == 3) {
				targetCanvas.setProgram(nextProgram(targetCanvas.getBank(), targetCanvas.getProgram(), 1));
				playPreview();
				repaint();
			} else if (menuFocus == 5) {
				targetCanvas.setFretStepSkip(targetCanvas.getFretStepSkip() + 1);
				repaint();
			} else if (menuFocus == 6) {
				targetCanvas.setMaxFrets(targetCanvas.getMaxFrets() + 1);
				repaint();
			} else if (menuFocus == 7) {
				targetCanvas.setMaxHarmonic(targetCanvas.getMaxHarmonic() + 1);
				repaint();
			}
		}

		void enter() {
			if (menuFocus == 1 && targetCanvas != null) {
				File chosen = sfChoiceFile(selectedSfIndex);
				if (chosen != null && !loadedSoundbanks.containsKey(chosen)) {
					try { loadedSoundbanks.put(chosen, MidiSystem.getSoundbank(chosen)); }
					catch (Exception ignored) {}
				}
				targetCanvas.setSoundfontFile(chosen);
				playPreview();
				repaint();
			} else if (menuFocus == 9) {
				commitEdo(); commitFreq();
				stringEditorPanel.prepare(targetCanvas);
				cardLayout.show(cardPanel, stringEditorCardKey);
			} else if (menuFocus == 10) {
				commitEdo(); commitFreq();
				additionalFretEditorPanel.prepare(targetCanvas);
				cardLayout.show(cardPanel, additionalFretEditorCardKey);
			}
		}

		void playPreview() {
			if (isPlaying.get()) {
				sendProgramChange(targetCanvas, targetCanvas.getBank(), targetCanvas.getProgram());
			} else {
				resetSynths();
			}
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

			double uiScaleValue = displayScale;
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

			// Name row
			{
				boolean focused = menuFocus == 0;
				String label = "Name:";
				String value = nameBuffer.toString();
				int labelW = fm.stringWidth(label);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(label, x, y);
				g.setPaint(!nameBufferValid() ? Color.RED : focused ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + value + (focused ? "|" : ""), x + labelW, y);
			}

			y += lineH + 8;

			// Soundfont File row
			{
				boolean focused = menuFocus == 1;
				String label = "Soundfont File:";
				String selected = sfChoiceName(selectedSfIndex);
				int loadedIdx = sfIndexForCanvas(targetCanvas);
				int labelW = fm.stringWidth(label);
				int selectedW = fm.stringWidth(" " + selected);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(label, x, y);
				g.setPaint(focused ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + selected, x + labelW, y);
				if (selectedSfIndex != loadedIdx) {
					g.setPaint(new Color(100, 100, 100));
					String loadedName = sfChoiceName(loadedIdx);
					g.drawString("  (loaded: " + loadedName + "  ENTER to apply)", x + labelW + selectedW, y);
				}
			}

			y += lineH + 8;

			// Bank row (LEFT/RIGHT adjustable)
			drawRow(g, fm, x, y, 2, "Bank:",
				String.valueOf(targetCanvas.getBank()));

			y += lineH + 8;

			// Instrument row
			{
				boolean focused = menuFocus == 3;
				String label = "Instrument:";
				String value = String.valueOf(targetCanvas.getProgram());
				int labelW = fm.stringWidth(label);
				int valueW = fm.stringWidth(" " + value);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(label, x, y);
				g.setPaint(focused ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + value, x + labelW, y);
				String instName = getInstrumentName(targetCanvas);
				if (!instName.isEmpty()) {
					g.setPaint(new Color(120, 120, 120));
					g.drawString("  " + instName, x + labelW + valueW, y);
				}
			}

			y += lineH + 8;

			{
				boolean focused = menuFocus == 4;
				String label = "EDO (ed2):";
				String value = edoBuffer.toString();
				boolean valid = edoBufferValid();
				int labelW = fm.stringWidth(label);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(label, x, y);
				g.setPaint(!valid ? Color.RED : focused ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + value, x + labelW, y);
			}

			y += lineH + 8;

			drawRow(g, fm, x, y, 5, "Fret Step Skip:",
				String.valueOf(targetCanvas.getFretStepSkip()));

			y += lineH + 8;

			drawRow(g, fm, x, y, 6, "Max Frets:",
				String.valueOf(targetCanvas.getMaxFrets()));

			y += lineH + 8;

			drawRow(g, fm, x, y, 7, "Max Harmonic:",
				String.valueOf(targetCanvas.getMaxHarmonic()));

			y += lineH + 8;

			{
				boolean focused = menuFocus == 8;
				String label = "Base Frequency:";
				String value = freqBuffer.toString();
				boolean valid = freqBufferValid();
				int labelW = fm.stringWidth(label);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(label, x, y);
				g.setPaint(!valid ? Color.RED : focused ? new Color(255, 255, 100) : Color.GRAY);
				g.drawString(" " + value, x + labelW, y);
			}

			y += lineH + 8;

			drawButton(g, fm, x, y, 9, "Edit Strings");

			y += lineH + 8;

			drawButton(g, fm, x, y, 10, "Edit Additional Frets");

			y += lineH * 2;
			g.setPaint(new Color(70, 70, 70));
			g.drawString("UP/DOWN: navigate   LEFT/RIGHT: adjust   ENTER: apply/open   ESC: apply & back", x, y);
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

		private void drawButton(Graphics2D g, FontMetrics fm, int x, int y, int itemIndex, String label) {
			boolean focused = menuFocus == itemIndex;
			String text = "[ " + label + " ]";
			g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString(text, x, y);
		}
	}

	class StringEditorPanel extends JPanel {
		StringCanvasConfig targetCanvas = null;
		int focusRow = 0, focusCol = 3;
		StringBuffer[] pitchBuffers = new StringBuffer[0];

		StringEditorPanel() {
			InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap am = getActionMap();
			im.put(k_Escape, "sep_esc");
			am.put("sep_esc", rToA(this::esc));
			im.put(k_Up, "sep_up");
			am.put("sep_up", rToA(this::up));
			im.put(k_Down, "sep_down");
			am.put("sep_down", rToA(this::down));
			im.put(k_Left, "sep_left");
			am.put("sep_left", rToA(this::left));
			im.put(k_Right, "sep_right");
			am.put("sep_right", rToA(this::right));
			im.put(k_Enter, "sep_enter");
			am.put("sep_enter", rToA(this::enter));
			im.put(k_Backspace, "sep_bs");
			am.put("sep_bs", rToA(this::pitchBackspace));
			for (char c = '0'; c <= '9'; c++) {
				char c_ = c;
				im.put(KeyStroke.getKeyStroke("" + c), "sep_" + c);
				am.put("sep_" + c, rToA(() -> pitchInput(c_)));
			}
			im.put(KeyStroke.getKeyStroke("PERIOD"), "sep_dot");
			am.put("sep_dot", rToA(() -> pitchInput('.')));
			im.put(k_Hyphen, "sep_minus");
			am.put("sep_minus", rToA(() -> pitchInput('-')));
		}

		void prepare(StringCanvasConfig canvas) {
			targetCanvas = canvas;
			focusRow = 0;
			focusCol = 3;
			refreshBuffers();
		}

		private void refreshBuffers() {
			double[] steps = targetCanvas.getEdoSteps();
			pitchBuffers = new StringBuffer[steps.length];
			for (int i = 0; i < steps.length; i++) {
				pitchBuffers[i] = new StringBuffer(fmtFloat(steps[i]));
			}
		}

		private boolean pitchValid(int row) {
			try { Double.parseDouble(pitchBuffers[row].toString()); return true; }
			catch (NumberFormatException e) { return false; }
		}

		private void commitPitch(int row) {
			if (targetCanvas == null || row >= pitchBuffers.length) return;
			if (pitchValid(row)) {
				targetCanvas.getEdoSteps()[row] = Double.parseDouble(pitchBuffers[row].toString());
			}
			pitchBuffers[row].setLength(0);
			pitchBuffers[row].append(fmtFloat(targetCanvas.getEdoSteps()[row]));
		}

		private void pitchInput(char c) {
			if (focusCol != 3 || targetCanvas == null || focusRow >= pitchBuffers.length) return;
			// only allow one minus at start
			if (c == '-' && pitchBuffers[focusRow].length() > 0) return;
			pitchBuffers[focusRow].append(c);
			repaint();
		}

		private void pitchBackspace() {
			if (focusCol != 3 || targetCanvas == null || focusRow >= pitchBuffers.length) return;
			if (pitchBuffers[focusRow].length() > 0)
				pitchBuffers[focusRow].deleteCharAt(pitchBuffers[focusRow].length() - 1);
			repaint();
		}

		// returns valid cols for a given row (n = add-string row)
		private int[] validCols(int row) {
			if (targetCanvas == null) return new int[]{3};
			int n = pitchBuffers.length;
			if (row == n) return new int[]{0}; // add button
			java.util.List<Integer> cols = new java.util.ArrayList<>();
			if (n > 1) cols.add(0);   // [x] only if deletable
			if (row > 0) cols.add(1); // [↑]
			if (row < n - 1) cols.add(2); // [↓]
			cols.add(3); // pitch field always present
			return cols.stream().mapToInt(Integer::intValue).toArray();
		}

		private int clampCol(int row, int col) {
			int[] valid = validCols(row);
			for (int v : valid) if (v == col) return col;
			// default to last valid (pitch field or button)
			return valid[valid.length - 1];
		}

		private int nextCol(int row, int col) {
			int[] valid = validCols(row);
			for (int i = 0; i < valid.length - 1; i++) if (valid[i] == col) return valid[i + 1];
			return valid[valid.length - 1];
		}

		private int prevCol(int row, int col) {
			int[] valid = validCols(row);
			for (int i = valid.length - 1; i > 0; i--) if (valid[i] == col) return valid[i - 1];
			return valid[0];
		}

		void esc() {
			if (targetCanvas != null && focusRow < pitchBuffers.length) commitPitch(focusRow);
			cardLayout.show(cardPanel, instrumentSettingsCardKey);
		}

		void up() {
			if (targetCanvas == null) return;
			if (focusRow < pitchBuffers.length) commitPitch(focusRow);
			focusRow = Math.max(0, focusRow - 1);
			focusCol = clampCol(focusRow, focusCol);
			repaint();
		}

		void down() {
			if (targetCanvas == null) return;
			if (focusRow < pitchBuffers.length) commitPitch(focusRow);
			focusRow = Math.min(pitchBuffers.length, focusRow + 1);
			focusCol = clampCol(focusRow, focusCol);
			repaint();
		}

		void left() {
			focusCol = prevCol(focusRow, focusCol);
			repaint();
		}

		void right() {
			focusCol = nextCol(focusRow, focusCol);
			repaint();
		}

		void enter() {
			if (targetCanvas == null) return;
			int n = pitchBuffers.length;
			if (focusRow == n) {
				// Add String — clone last
				commitPitch(n - 1);
				double[] old = targetCanvas.getEdoSteps();
				double[] next = java.util.Arrays.copyOf(old, old.length + 1);
				next[next.length - 1] = old[old.length - 1];
				targetCanvas.setEdoSteps(next);
				refreshBuffers();
				focusRow = next.length; // move to new add-button row? keep on new string
				focusRow = next.length - 1;
				focusCol = clampCol(focusRow, focusCol);
			} else if (focusCol == 0 && n > 1) {
				// Delete
				commitPitch(focusRow);
				double[] old = targetCanvas.getEdoSteps();
				double[] next = new double[old.length - 1];
				System.arraycopy(old, 0, next, 0, focusRow);
				System.arraycopy(old, focusRow + 1, next, focusRow, old.length - focusRow - 1);
				targetCanvas.setEdoSteps(next);
				focusRow = Math.min(focusRow, next.length - 1);
				refreshBuffers();
				focusCol = clampCol(focusRow, focusCol);
			} else if (focusCol == 1 && focusRow > 0) {
				// Move up — swap in-place
				commitPitch(focusRow);
				commitPitch(focusRow - 1);
				double[] steps = targetCanvas.getEdoSteps();
				double tmp = steps[focusRow]; steps[focusRow] = steps[focusRow - 1]; steps[focusRow - 1] = tmp;
				StringBuffer tbuf = pitchBuffers[focusRow]; pitchBuffers[focusRow] = pitchBuffers[focusRow - 1]; pitchBuffers[focusRow - 1] = tbuf;
				focusRow--;
			} else if (focusCol == 2 && focusRow < n - 1) {
				// Move down — swap in-place
				commitPitch(focusRow);
				commitPitch(focusRow + 1);
				double[] steps = targetCanvas.getEdoSteps();
				double tmp = steps[focusRow]; steps[focusRow] = steps[focusRow + 1]; steps[focusRow + 1] = tmp;
				StringBuffer tbuf = pitchBuffers[focusRow]; pitchBuffers[focusRow] = pitchBuffers[focusRow + 1]; pitchBuffers[focusRow + 1] = tbuf;
				focusRow++;
			}
			repaint();
		}

		@Override
		public void paint(Graphics g_) {
			if (targetCanvas == null) return;
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(Color.BLACK);
			g.fill(getBounds());

			double uiScaleValue = displayScale;
			Font scaledFont = textFont.deriveFont((float)(textFont.getSize() * uiScaleValue / UI_SCALE));
			FontMetrics fm = getFontMetrics(scaledFont);
			g.setFont(scaledFont);

			int lineH = fm.getHeight();
			int x = 10;
			int y = lineH;

			g.setPaint(Color.WHITE);
			g.drawString("Edit Strings: " + targetCanvas.getName(), x, y);
			y += lineH + 4;

			int n = pitchBuffers.length;
			int colX   = x;       // [x]
			int colUp  = colX + fm.stringWidth("[x]  ");
			int colDn  = colUp + fm.stringWidth("[↑]  ");
			int colPitch = colDn + fm.stringWidth("[↓]  ");

			g.setPaint(new Color(100, 100, 100));
			g.drawString("steps of " + fmtFloat(targetCanvas.getEd2()) +
					" equal divisions of the second partial", colPitch, y);
			y += lineH + 2;
			g.drawString("base pitch " + fmtFloat(targetCanvas.getBaseFrequency()), colPitch, y);
			y += lineH + 4;

			for (int i = 0; i < n; i++) {
				boolean rowFocused = focusRow == i;

				// [x]
				if (n > 1) {
					boolean focused = rowFocused && focusCol == 0;
					g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
					g.drawString("[x]", colX, y);
				}

				// [↑]
				if (i > 0) {
					boolean focused = rowFocused && focusCol == 1;
					g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
					g.drawString("[↑]", colUp, y);
				}

				// [↓]
				if (i < n - 1) {
					boolean focused = rowFocused && focusCol == 2;
					g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
					g.drawString("[↓]", colDn, y);
				}

				// pitch field
				{
					boolean focused = rowFocused && focusCol == 3;
					boolean valid = pitchValid(i);
					g.setPaint(!valid ? Color.RED : focused ? new Color(255, 255, 100) : Color.GRAY);
					g.drawString(pitchBuffers[i].toString(), colPitch, y);
				}

				y += lineH + 4;
			}

			// Add String button
			{
				boolean focused = focusRow == n;
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString("[ Add String ]", x, y);
				y += lineH * 2;
			}

			g.setPaint(new Color(70, 70, 70));
			g.drawString("UP/DOWN: rows   LEFT/RIGHT: field   ENTER: activate   ESC: back", x, y);
		}
	}

	class DrumEditorPanel extends JPanel {
		DrumCanvasConfig targetCanvas = null;
		int focusRow = 0, focusCol = 0;
		private final StringBuffer nameBuffer = new StringBuffer();

		java.util.List<PercRowType>  rowBuf       = new java.util.ArrayList<>();
		java.util.List<StringBuffer> tokenNameBuf = new java.util.ArrayList<>();
		java.util.List<PercRowType>  tokenPosBuf  = new java.util.ArrayList<>();
		java.util.List<StringBuffer> tokenMidiBuf = new java.util.ArrayList<>();

		int selectedSfIndex = 0;

		private int sfChoiceCount() { return sf2Files.size() + 1; }
		private File sfChoiceFile(int idx) { return idx == 0 ? null : sf2Files.get(idx - 1); }
		private String sfChoiceName(int idx) { return idx == 0 ? "Default" : sf2Files.get(idx - 1).getName(); }
		private int sfIndexForDrum(DrumCanvasConfig canvas) {
			if (!canvas.getSoundfontFile().isPresent()) return 0;
			File current = canvas.getSoundfontFile().get();
			for (int i = 0; i < sf2Files.size(); i++)
				if (sf2Files.get(i).getName().equals(current.getName())) return i + 1;
			return 0;
		}

		// --- focus index helpers ---
		// row 0 = Name, 1 = Soundfont, 2 = Bank, 3 = Program, 4.. = Rows, then Tokens
		int addRowIdx()     { return 4 + rowBuf.size(); }
		int firstTokIdx()   { return 5 + rowBuf.size(); }
		int addTokIdx()     { return 5 + rowBuf.size() + tokenNameBuf.size(); }
		int maxFocusRow()   { return addTokIdx(); }
		boolean isRowItem(int r)  { return r >= 4 && r < addRowIdx(); }
		boolean isTokItem(int r)  { return r >= firstTokIdx() && r < addTokIdx(); }
		int rowIdx(int r)         { return r - 4; }
		int tokIdx(int r)         { return r - firstTokIdx(); }

		int[] validCols(int row) {
			if (row == 0 || row == 1 || row == 2 || row == 3) return new int[]{0};
			if (row == addRowIdx() || row == addTokIdx()) return new int[]{0};
			java.util.List<Integer> cols = new java.util.ArrayList<>();
			if (isRowItem(row)) {
				int i = rowIdx(row);
				if (rowBuf.size() > 1) cols.add(0);
				if (i > 0) cols.add(1);
				if (i < rowBuf.size() - 1) cols.add(2);
				cols.add(3);
			} else if (isTokItem(row)) {
				int i = tokIdx(row);
				if (tokenNameBuf.size() > 1) cols.add(0);
				if (i > 0) cols.add(1);
				if (i < tokenNameBuf.size() - 1) cols.add(2);
				cols.add(3); cols.add(4); cols.add(5);
			}
			if (cols.isEmpty()) cols.add(0);
			return cols.stream().mapToInt(Integer::intValue).toArray();
		}

		int clampCol(int row, int col) {
			int[] v = validCols(row);
			for (int c : v) if (c == col) return col;
			return v[v.length - 1];
		}
		int nextCol(int row, int col) {
			int[] v = validCols(row); for (int i = 0; i < v.length - 1; i++) if (v[i] == col) return v[i+1]; return v[v.length-1];
		}
		int prevCol(int row, int col) {
			int[] v = validCols(row); for (int i = v.length-1; i > 0; i--) if (v[i] == col) return v[i-1]; return v[0];
		}

		DrumEditorPanel() {
			InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap am = getActionMap();
			im.put(k_Escape,    "dep_esc");   am.put("dep_esc",   rToA(this::esc));
			im.put(k_Up,        "dep_up");    am.put("dep_up",    rToA(this::up));
			im.put(k_Down,      "dep_down");  am.put("dep_down",  rToA(this::down));
			im.put(k_Left,      "dep_left");  am.put("dep_left",  rToA(this::left));
			im.put(k_Right,     "dep_right"); am.put("dep_right", rToA(this::right));
			im.put(k_Enter,     "dep_enter"); am.put("dep_enter", rToA(this::enter));
			im.put(k_Backspace, "dep_bs");    am.put("dep_bs",    rToA(this::backspace));
			im.put(k_Space,     "dep_space"); am.put("dep_space", rToA(this::toggleFocused));
			for (char c = '0'; c <= '9'; c++) {
				char c_ = c;
				im.put(KeyStroke.getKeyStroke("" + c), "dep_d" + c);
				am.put("dep_d" + c, rToA(() -> input(c_)));
			}
			for (char c = 'A'; c <= 'Z'; c++) {
				char lo = Character.toLowerCase(c), up = c;
				im.put(KeyStroke.getKeyStroke(up + ""),         "dep_lo" + lo);
				im.put(KeyStroke.getKeyStroke("shift " + up),   "dep_up" + up);
				am.put("dep_lo" + lo, rToA(() -> input(lo)));
				am.put("dep_up" + up, rToA(() -> input(up)));
			}
		}

		void prepare(DrumCanvasConfig canvas) {
			targetCanvas = canvas;
			focusRow = 0; focusCol = 0;
			nameBuffer.setLength(0);
			nameBuffer.append(canvas.getName());
			selectedSfIndex = sfIndexForDrum(canvas);
			canvas.getSoundfontFile().ifPresent(f -> {
				if (!loadedSoundbanks.containsKey(f)) {
					try { loadedSoundbanks.put(f, MidiSystem.getSoundbank(f)); }
					catch (Exception ignored) {}
				}
			});
			rowBuf.clear();
			rowBuf.addAll(canvas.getRowTypes());
			tokenNameBuf.clear(); tokenPosBuf.clear(); tokenMidiBuf.clear();
			for (PercToken t : canvas.getTokens()) {
				tokenNameBuf.add(new StringBuffer(t.getToken()));
				tokenPosBuf.add(t.getPosition());
				tokenMidiBuf.add(new StringBuffer(String.valueOf(t.getMidiNumber())));
			}
		}

		void flush() {
			if (targetCanvas == null) return;
			targetCanvas.setRowTypes(new java.util.ArrayList<>(rowBuf));
			java.util.List<PercToken> toks = new java.util.ArrayList<>();
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (int i = 0; i < tokenNameBuf.size(); i++) {
				String name = tokenNameBuf.get(i).toString();
				if (name.isEmpty() || seen.contains(name.toUpperCase())) continue;
				try {
					int midi = Integer.parseInt(tokenMidiBuf.get(i).toString());
					if (midi < 0) continue;
					toks.add(new PercToken(name, tokenPosBuf.get(i), midi));
					seen.add(name.toUpperCase());
				} catch (NumberFormatException ignored) {}
			}
			targetCanvas.setTokens(toks);
		}

		boolean nameValid(int i) {
			String n = tokenNameBuf.get(i).toString();
			if (n.isEmpty()) return false;
			for (int j = 0; j < tokenNameBuf.size(); j++)
				if (j != i && tokenNameBuf.get(j).toString().equalsIgnoreCase(n)) return false;
			return true;
		}
		boolean midiValid(int i) {
			try { return Integer.parseInt(tokenMidiBuf.get(i).toString()) >= 0; }
			catch (NumberFormatException e) { return false; }
		}

		private void commitName() {
			if (targetCanvas == null) return;
			String newName = nameBuffer.toString();
			if (newName.isEmpty()) { nameBuffer.setLength(0); nameBuffer.append(targetCanvas.getName()); return; }
			if (!newName.equals(targetCanvas.getName())) {
				targetCanvas.setName(newName);
			}
		}

		void playPreviewDrum() {
			if (isPlaying.get()) {
				sendProgramChange(targetCanvas, targetCanvas.getBank(), targetCanvas.getProgram());
			} else {
				resetSynths();
			}
			if (!tokenNameBuf.isEmpty())
				playPreviewNote(targetCanvas, tokenNameBuf.get(0).toString(), 0);
		}

		void input(char c) {
			if (focusRow == 0) { nameBuffer.append(c); repaint(); return; }
			if (!isTokItem(focusRow)) return;
			int i = tokIdx(focusRow);
			if (focusCol == 3) {
				if (c == '-') return;
				tokenNameBuf.get(i).append(c);
				flush(); repaint();
			} else if (focusCol == 5) {
				if (c < '0' || c > '9') return;
				tokenMidiBuf.get(i).append(c);
				flush(); repaint();
				if (midiValid(i) && nameValid(i))
					playPreviewNote(targetCanvas, tokenNameBuf.get(i).toString(), 0);
			}
		}

		void backspace() {
			if (focusRow == 0 && nameBuffer.length() > 0) { nameBuffer.deleteCharAt(nameBuffer.length()-1); repaint(); return; }
			if (!isTokItem(focusRow)) return;
			int i = tokIdx(focusRow);
			StringBuffer buf = focusCol == 3 ? tokenNameBuf.get(i)
			                 : focusCol == 5 ? tokenMidiBuf.get(i) : null;
			if (buf != null && buf.length() > 0) {
				buf.deleteCharAt(buf.length()-1); flush(); repaint();
				if (focusCol == 5 && midiValid(i) && nameValid(i))
					playPreviewNote(targetCanvas, tokenNameBuf.get(i).toString(), 0);
			}
		}

		void esc() { commitName(); flush(); cardLayout.show(cardPanel, mainInterfaceCardKey); }

		void up() {
			focusRow = Math.max(0, focusRow - 1);
			// skip non-interactive section header gaps — none in flat list, but skip invalid indices
			focusCol = clampCol(focusRow, focusCol);
			repaint();
		}

		void down() {
			if (focusRow == 0) commitName();
			focusRow = Math.min(maxFocusRow(), focusRow + 1);
			focusCol = clampCol(focusRow, focusCol);
			repaint();
		}

		void left() {
			if (focusRow == 1) { selectedSfIndex = Math.max(0, selectedSfIndex - 1); repaint(); return; }
			if (focusRow == 2) { targetCanvas.setBank(Math.max(0, targetCanvas.getBank()-1)); playPreviewDrum(); repaint(); return; }
			if (focusRow == 3) { targetCanvas.setProgram(Math.max(0, targetCanvas.getProgram()-1)); playPreviewDrum(); repaint(); return; }
			int next = prevCol(focusRow, focusCol);
			if (next == focusCol && focusRow > 2) {
				focusRow--;
				int[] v = validCols(focusRow); focusCol = v[v.length - 1];
			} else {
				focusCol = next;
			}
			repaint();
		}

		void right() {
			if (focusRow == 1) { selectedSfIndex = Math.min(sfChoiceCount() - 1, selectedSfIndex + 1); repaint(); return; }
			if (focusRow == 2) { targetCanvas.setBank(targetCanvas.getBank()+1); playPreviewDrum(); repaint(); return; }
			if (focusRow == 3) { targetCanvas.setProgram(targetCanvas.getProgram()+1); playPreviewDrum(); repaint(); return; }
			int next = nextCol(focusRow, focusCol);
			if (next == focusCol && focusRow < maxFocusRow()) {
				focusRow++;
				focusCol = validCols(focusRow)[0];
			} else {
				focusCol = next;
			}
			repaint();
		}

		void toggleFocused() {
			if (focusRow == 0) { input(' '); return; }
			if (isRowItem(focusRow) && focusCol == 3) toggleRow(rowIdx(focusRow));
			else if (isTokItem(focusRow) && focusCol == 4) toggleTok(tokIdx(focusRow));
		}

		void toggleRow(int i) {
			rowBuf.set(i, rowBuf.get(i) == PercRowType.HAND ? PercRowType.FOOT : PercRowType.HAND);
			flush(); repaint();
		}

		void toggleTok(int i) {
			tokenPosBuf.set(i, tokenPosBuf.get(i) == PercRowType.HAND ? PercRowType.FOOT : PercRowType.HAND);
			flush(); repaint();
		}

		String nextDefaultToken() {
			java.util.Set<String> used = new java.util.HashSet<>();
			for (StringBuffer sb : tokenNameBuf) used.add(sb.toString().toUpperCase());
			for (char c = 'A'; c <= 'Z'; c++) if (!used.contains(String.valueOf(c))) return String.valueOf(c);
			return "A";
		}

		void enter() {
			if (focusRow == 1 && targetCanvas != null) {
				File chosen = sfChoiceFile(selectedSfIndex);
				if (chosen != null && !loadedSoundbanks.containsKey(chosen)) {
					try { loadedSoundbanks.put(chosen, MidiSystem.getSoundbank(chosen)); }
					catch (Exception ignored) {}
				}
				targetCanvas.setSoundfontFile(chosen);
				playPreviewDrum();
				repaint();
				return;
			}
			if (focusRow == addRowIdx()) {
				rowBuf.add(PercRowType.HAND);
				flush(); repaint(); return;
			}
			if (focusRow == addTokIdx()) {
				tokenNameBuf.add(new StringBuffer(nextDefaultToken()));
				tokenPosBuf.add(PercRowType.HAND);
				tokenMidiBuf.add(new StringBuffer("0"));
				focusRow = addTokIdx() - 1;
				focusCol = clampCol(focusRow, 3);
				flush(); repaint(); return;
			}
			if (isRowItem(focusRow)) {
				int i = rowIdx(focusRow);
				if (focusCol == 0 && rowBuf.size() > 1) {
					rowBuf.remove(i);
					focusRow = Math.min(focusRow, addRowIdx() - 1);
					focusCol = clampCol(focusRow, focusCol);
					flush();
				} else if (focusCol == 1 && i > 0) {
					java.util.Collections.swap(rowBuf, i, i-1); focusRow--;
					flush();
				} else if (focusCol == 2 && i < rowBuf.size()-1) {
					java.util.Collections.swap(rowBuf, i, i+1); focusRow++;
					flush();
				} else if (focusCol == 3) {
					toggleFocused(); return;
				}
				repaint(); return;
			}
			if (isTokItem(focusRow)) {
				int i = tokIdx(focusRow);
				boolean deleted = false;
				if (focusCol == 0 && tokenNameBuf.size() > 1) {
					tokenNameBuf.remove(i); tokenPosBuf.remove(i); tokenMidiBuf.remove(i);
					focusRow = Math.min(focusRow, addTokIdx() - 1);
					focusCol = clampCol(focusRow, focusCol);
					flush(); deleted = true;
				} else if (focusCol == 1 && i > 0) {
					swap(tokenNameBuf, i, i-1); swap(tokenPosBuf, i, i-1); swap(tokenMidiBuf, i, i-1); focusRow--;
					flush();
				} else if (focusCol == 2 && i < tokenNameBuf.size()-1) {
					swap(tokenNameBuf, i, i+1); swap(tokenPosBuf, i, i+1); swap(tokenMidiBuf, i, i+1); focusRow++;
					flush();
				} else if (focusCol == 4) {
					toggleFocused();
				}
				if (!deleted && isTokItem(focusRow))
					playPreviewNote(targetCanvas, tokenNameBuf.get(tokIdx(focusRow)).toString(), 0);
				repaint();
			}
		}

		private <T> void swap(java.util.List<T> list, int a, int b) {
			T tmp = list.get(a); list.set(a, list.get(b)); list.set(b, tmp);
		}

		@Override
		public void paint(Graphics g_) {
			if (targetCanvas == null) return;
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(Color.BLACK); g.fill(getBounds());

			double uiScaleValue = displayScale;
			Font scaledFont = textFont.deriveFont((float)(textFont.getSize() * uiScaleValue / UI_SCALE));
			FontMetrics fm = getFontMetrics(scaledFont);
			g.setFont(scaledFont);

			int lineH = fm.getHeight();
			int x = 10, y = lineH;

			g.setPaint(Color.WHITE);
			g.drawString("Drum Editor: " + targetCanvas.getName(), x, y);
			y += lineH + 4;

			// Name
			{
				boolean focused = focusRow == 0;
				String label = "Name:";
				String value = nameBuffer.toString();
				int labelW = fm.stringWidth(label);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(label, x, y);
				g.setPaint(value.isEmpty() ? Color.RED : focused ? new Color(255,255,100) : Color.GRAY);
				g.drawString(" " + value + (focused ? "|" : ""), x + labelW, y);
			}
			y += lineH + 6;

			// Soundfont File
			{
				boolean focused = focusRow == 1;
				String label = "Soundfont File:";
				String selected = sfChoiceName(selectedSfIndex);
				int loadedIdx = sfIndexForDrum(targetCanvas);
				int lw = fm.stringWidth(label);
				int selW = fm.stringWidth(" " + selected);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(label, x, y);
				g.setPaint(focused ? new Color(255,255,100) : Color.GRAY);
				g.drawString(" " + selected, x + lw, y);
				if (selectedSfIndex != loadedIdx) {
					g.setPaint(new Color(100,100,100));
					g.drawString("  (loaded: " + sfChoiceName(loadedIdx) + "  ENTER to apply)", x + lw + selW, y);
				}
			}
			y += lineH + 6;

			// Bank
			drawDepRow(g, fm, x, y, 2, "Bank:", String.valueOf(targetCanvas.getBank()), null);
			y += lineH + 6;

			// Program
			{
				boolean focused = focusRow == 3;
				String label = "Program:", value = String.valueOf(targetCanvas.getProgram());
				int lw = fm.stringWidth(label), vw = fm.stringWidth(" " + value);
				g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
				g.drawString(label, x, y);
				g.setPaint(focused ? new Color(255,255,100) : Color.GRAY);
				g.drawString(" " + value, x + lw, y);
				String instName = getInstrumentName(targetCanvas);
				if (!instName.isEmpty()) { g.setPaint(new Color(120,120,120)); g.drawString("  " + instName, x + lw + vw, y); }
			}
			y += lineH + 10;

			// --- Rows ---
			int colX = x, colUp = colX + fm.stringWidth("[x]  "), colDn = colUp + fm.stringWidth("[↑]  "), colToggle = colDn + fm.stringWidth("[↓]  ");
			g.setPaint(new Color(80,80,80));
			g.drawString("--- Rows ---", x, y);
			y += lineH + 4;

			for (int i = 0; i < rowBuf.size(); i++) {
				int frow = i + 2;
				boolean rf = focusRow == frow;
				if (rowBuf.size() > 1) {
					g.setPaint(rf && focusCol==0 ? Color.WHITE : Color.DARK_GRAY); g.drawString("[x]", colX, y);
				}
				if (i > 0) {
					g.setPaint(rf && focusCol==1 ? Color.WHITE : Color.DARK_GRAY); g.drawString("[↑]", colUp, y);
				}
				if (i < rowBuf.size()-1) {
					g.setPaint(rf && focusCol==2 ? Color.WHITE : Color.DARK_GRAY); g.drawString("[↓]", colDn, y);
				}
				boolean togFocused = rf && focusCol==3;
				g.setPaint(togFocused ? new Color(255,255,100) : Color.GRAY);
				g.drawString(rowBuf.get(i).toString(), colToggle, y);
				y += lineH + 4;
			}
			g.setPaint(focusRow == addRowIdx() ? Color.WHITE : Color.DARK_GRAY);
			g.drawString("[ Add Row ]", x, y);
			y += lineH + 10;

			// --- Tokens ---
			int colTokName = colDn + fm.stringWidth("[↓]  ");
			int colTokPos  = colTokName + fm.stringWidth("MMMM  ");
			int colTokMidi = colTokPos  + fm.stringWidth("HAND  ");

			g.setPaint(new Color(80,80,80));
			g.drawString("--- Tokens ---", x, y);
			y += lineH + 2;
			g.setPaint(new Color(100,100,100));
			g.drawString("name", colTokName, y);
			g.drawString("pos",  colTokPos,  y);
			g.drawString("midi", colTokMidi, y);
			y += lineH + 4;

			for (int i = 0; i < tokenNameBuf.size(); i++) {
				int frow = firstTokIdx() + i;
				boolean rf = focusRow == frow;
				if (tokenNameBuf.size() > 1) {
					g.setPaint(rf && focusCol==0 ? Color.WHITE : Color.DARK_GRAY); g.drawString("[x]", colX, y);
				}
				if (i > 0) {
					g.setPaint(rf && focusCol==1 ? Color.WHITE : Color.DARK_GRAY); g.drawString("[↑]", colUp, y);
				}
				if (i < tokenNameBuf.size()-1) {
					g.setPaint(rf && focusCol==2 ? Color.WHITE : Color.DARK_GRAY); g.drawString("[↓]", colDn, y);
				}
				// name field
				boolean nameFoc = rf && focusCol==3;
				boolean nameOk = nameValid(i);
				g.setPaint(!nameOk ? Color.RED : nameFoc ? new Color(255,255,100) : Color.GRAY);
				g.drawString(tokenNameBuf.get(i).toString(), colTokName, y);
				// position toggle
				boolean posFoc = rf && focusCol==4;
				g.setPaint(posFoc ? new Color(255,255,100) : Color.GRAY);
				g.drawString(tokenPosBuf.get(i).toString(), colTokPos, y);
				// midi field
				boolean midiFoc = rf && focusCol==5;
				boolean midiOk = midiValid(i);
				g.setPaint(!midiOk ? Color.RED : midiFoc ? new Color(255,255,100) : Color.GRAY);
				g.drawString(tokenMidiBuf.get(i).toString(), colTokMidi, y);
				y += lineH + 4;
			}
			g.setPaint(focusRow == addTokIdx() ? Color.WHITE : Color.DARK_GRAY);
			g.drawString("[ Add Token ]", x, y);
			y += lineH * 2;

			g.setPaint(new Color(70,70,70));
			g.drawString("UP/DOWN: rows   LEFT/RIGHT: navigate   ENTER/SPACE: toggle   ESC: back", x, y);
		}

		private void drawDepRow(Graphics2D g, FontMetrics fm, int x, int y, int row,
				String label, String value, String suffix) {
			boolean focused = focusRow == row;
			int lw = fm.stringWidth(label);
			g.setPaint(focused ? Color.WHITE : Color.DARK_GRAY);
			g.drawString(label, x, y);
			g.setPaint(focused ? new Color(255,255,100) : Color.GRAY);
			g.drawString(" " + value, x + lw, y);
		}

		private String getInstrumentName(CanvasConfig config) {
			if (config.getSoundfontFile().isPresent()) {
				File f = config.getSoundfontFile().get();
				Soundbank sb = loadedSoundbanks.get(f);
				if (sb != null) for (Instrument inst : sb.getInstruments())
					if (inst.getPatch().getBank() == config.getBank() && inst.getPatch().getProgram() == config.getProgram())
						return inst.getName();
				return "";
			}
			for (Map.Entry<DrumCanvasConfig,Synthesizer> e : drumSynths.entrySet()) {
				if (e.getKey() == config) {
					Soundbank sb = e.getValue().getDefaultSoundbank();
					if (sb != null) for (Instrument inst : sb.getInstruments())
						if (inst.getPatch().getBank() == config.getBank() && inst.getPatch().getProgram() == config.getProgram())
							return inst.getName();
					break;
				}
			}
			return "";
		}
	}

	class AdditionalFretEditorPanel extends JPanel {
		StringCanvasConfig targetCanvas = null;
		int focusRow = 0, focusCol = 3;
		// per-row working buffers: [0]=token, [1]=steps
		java.util.List<StringBuffer[]> rows = new java.util.ArrayList<>();

		AdditionalFretEditorPanel() {
			InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap am = getActionMap();
			im.put(k_Escape,    "afep_esc");   am.put("afep_esc",   rToA(this::esc));
			im.put(k_Up,        "afep_up");    am.put("afep_up",    rToA(this::up));
			im.put(k_Down,      "afep_down");  am.put("afep_down",  rToA(this::down));
			im.put(k_Left,      "afep_left");  am.put("afep_left",  rToA(this::left));
			im.put(k_Right,     "afep_right"); am.put("afep_right", rToA(this::right));
			im.put(k_Enter,     "afep_enter"); am.put("afep_enter", rToA(this::enter));
			im.put(k_Backspace, "afep_bs");    am.put("afep_bs",    rToA(this::backspace));
			// digits + dot + minus for steps field; also valid in token field
			for (char c = '0'; c <= '9'; c++) {
				char c_ = c;
				im.put(KeyStroke.getKeyStroke("" + c), "afep_" + c);
				am.put("afep_" + c, rToA(() -> input(c_)));
			}
			im.put(KeyStroke.getKeyStroke("PERIOD"), "afep_dot");   am.put("afep_dot",   rToA(() -> input('.')));
			im.put(k_Hyphen,                   "afep_minus"); am.put("afep_minus",  rToA(() -> input('-')));
			// letters for token field
			for (char c = 'A'; c <= 'Z'; c++) {
				char lo = Character.toLowerCase(c), up = c;
				im.put(KeyStroke.getKeyStroke(up + ""),          "afep_lo" + lo);
				im.put(KeyStroke.getKeyStroke("shift " + up),    "afep_up" + up);
				am.put("afep_lo" + lo, rToA(() -> input(lo)));
				am.put("afep_up" + up, rToA(() -> input(up)));
			}
			im.put(KeyStroke.getKeyStroke('_'), "afep_under"); am.put("afep_under", rToA(() -> input('_')));
			im.put(KeyStroke.getKeyStroke('#'), "afep_hash");  am.put("afep_hash",  rToA(() -> input('#')));
		}

		void prepare(StringCanvasConfig canvas) {
			targetCanvas = canvas;
			focusRow = 0;
			focusCol = 3;
			rows.clear();
			for (Map.Entry<String,Double> e : canvas.getAdditionalPitchMap().entrySet()) {
				rows.add(new StringBuffer[]{
					new StringBuffer(e.getKey()),
					new StringBuffer(fmtFloat(e.getValue()))
				});
			}
		}

		// flush all valid rows to the live config
		private void flush() {
			if (targetCanvas == null) return;
			java.util.LinkedHashMap<String,Double> map = new java.util.LinkedHashMap<>();
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (StringBuffer[] row : rows) {
				String tok = row[0].toString();
				if (tok.isEmpty() || seen.contains(tok)) continue;
				try {
					double steps = Double.parseDouble(row[1].toString());
					map.put(tok, steps);
					seen.add(tok);
				} catch (NumberFormatException ignored) {}
			}
			targetCanvas.setAdditionalPitchMap(map);
		}

		private boolean tokenValid(int i) {
			String tok = rows.get(i)[0].toString();
			if (tok.isEmpty()) return false;
			for (int j = 0; j < rows.size(); j++) {
				if (j != i && rows.get(j)[0].toString().equals(tok)) return false;
			}
			return true;
		}

		private boolean stepsValid(int i) {
			try { Double.parseDouble(rows.get(i)[1].toString()); return true; }
			catch (NumberFormatException e) { return false; }
		}

		private int[] validCols(int row) {
			if (row == rows.size()) return new int[]{0}; // add button
			java.util.List<Integer> cols = new java.util.ArrayList<>();
			cols.add(0); // [x] always — no minimum
			if (row > 0) cols.add(1);
			if (row < rows.size() - 1) cols.add(2);
			cols.add(3);
			cols.add(4);
			return cols.stream().mapToInt(Integer::intValue).toArray();
		}

		private int clampCol(int row, int col) {
			int[] valid = validCols(row);
			for (int v : valid) if (v == col) return col;
			return valid[valid.length - 1];
		}

		private int nextCol(int row, int col) {
			int[] valid = validCols(row);
			for (int i = 0; i < valid.length - 1; i++) if (valid[i] == col) return valid[i+1];
			return valid[valid.length - 1];
		}

		private int prevCol(int row, int col) {
			int[] valid = validCols(row);
			for (int i = valid.length - 1; i > 0; i--) if (valid[i] == col) return valid[i-1];
			return valid[0];
		}

		private void input(char c) {
			if (focusRow >= rows.size()) return;
			StringBuffer buf = rows.get(focusRow)[focusCol == 4 ? 1 : focusCol == 3 ? 0 : -1 < 0 ? null : null];
			if (focusCol == 3) buf = rows.get(focusRow)[0];
			else if (focusCol == 4) buf = rows.get(focusRow)[1];
			else return;
			// steps field: only allow digits, '.', '-' at start
			if (focusCol == 4) {
				boolean isDigit = c >= '0' && c <= '9';
				boolean isDot = c == '.';
				boolean isLeadingMinus = c == '-' && buf.length() == 0;
				if (!isDigit && !isDot && !isLeadingMinus) return;
			}
			// token field: skip minus (ambiguous in main parser)
			if (focusCol == 3 && c == '-') return;
			buf.append(c);
			flush();
			repaint();
		}

		private void backspace() {
			if (focusRow >= rows.size()) return;
			StringBuffer buf = focusCol == 3 ? rows.get(focusRow)[0]
			                 : focusCol == 4 ? rows.get(focusRow)[1] : null;
			if (buf == null) return;
			if (buf.length() > 0) buf.deleteCharAt(buf.length() - 1);
			flush();
			repaint();
		}

		void esc() {
			flush();
			cardLayout.show(cardPanel, instrumentSettingsCardKey);
		}

		void up() {
			flush();
			focusRow = Math.max(0, focusRow - 1);
			focusCol = clampCol(focusRow, focusCol);
			repaint();
		}

		void down() {
			flush();
			focusRow = Math.min(rows.size(), focusRow + 1);
			focusCol = clampCol(focusRow, focusCol);
			repaint();
		}

		void left()  { focusCol = prevCol(focusRow, focusCol); repaint(); }
		void right() { focusCol = nextCol(focusRow, focusCol); repaint(); }

		void enter() {
			int n = rows.size();
			if (focusRow == n) {
				// Add entry — pick next unused capital letter starting from A
				java.util.Set<String> used = new java.util.HashSet<>();
				for (StringBuffer[] r : rows) used.add(r[0].toString());
				String defaultToken = "A";
				for (char c = 'A'; c <= 'Z'; c++) {
					if (!used.contains(String.valueOf(c))) { defaultToken = String.valueOf(c); break; }
				}
				rows.add(new StringBuffer[]{ new StringBuffer(defaultToken), new StringBuffer("0.000") });
				focusRow = rows.size() - 1;
				focusCol = 3;
				flush();
			} else if (focusCol == 0) {
				// Delete
				rows.remove(focusRow);
				focusRow = Math.min(focusRow, rows.size() == 0 ? 0 : rows.size() - 1);
				if (rows.isEmpty()) focusRow = 0;
				focusCol = clampCol(focusRow, focusCol);
				flush();
			} else if (focusCol == 1 && focusRow > 0) {
				// Move up
				StringBuffer[] tmp = rows.get(focusRow); rows.set(focusRow, rows.get(focusRow-1)); rows.set(focusRow-1, tmp);
				focusRow--;
				flush();
			} else if (focusCol == 2 && focusRow < n - 1) {
				// Move down
				StringBuffer[] tmp = rows.get(focusRow); rows.set(focusRow, rows.get(focusRow+1)); rows.set(focusRow+1, tmp);
				focusRow++;
				flush();
			}
			repaint();
		}

		@Override
		public void paint(Graphics g_) {
			if (targetCanvas == null) return;
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(Color.BLACK);
			g.fill(getBounds());

			double uiScaleValue = displayScale;
			Font scaledFont = textFont.deriveFont((float)(textFont.getSize() * uiScaleValue / UI_SCALE));
			FontMetrics fm = getFontMetrics(scaledFont);
			g.setFont(scaledFont);

			int lineH = fm.getHeight();
			int x = 10, y = lineH;

			g.setPaint(Color.WHITE);
			g.drawString("Edit Additional Frets: " + targetCanvas.getName(), x, y);
			y += lineH + 4;

			int colX     = x;
			int colUp    = colX    + fm.stringWidth("[x]  ");
			int colDn    = colUp   + fm.stringWidth("[↑]  ");
			int colToken = colDn   + fm.stringWidth("[↓]  ");
			int colSteps = colToken + fm.stringWidth("MMMMMM  "); // fixed token column width

			g.setPaint(new Color(100, 100, 100));
			g.drawString("token", colToken, y);
			g.drawString("steps", colSteps, y);
			y += lineH + 4;

			for (int i = 0; i < rows.size(); i++) {
				boolean rowFocused = focusRow == i;
				StringBuffer[] row = rows.get(i);

				// [x]
				g.setPaint(rowFocused && focusCol == 0 ? Color.WHITE : Color.DARK_GRAY);
				g.drawString("[x]", colX, y);

				// [↑]
				if (i > 0) {
					g.setPaint(rowFocused && focusCol == 1 ? Color.WHITE : Color.DARK_GRAY);
					g.drawString("[↑]", colUp, y);
				}

				// [↓]
				if (i < rows.size() - 1) {
					g.setPaint(rowFocused && focusCol == 2 ? Color.WHITE : Color.DARK_GRAY);
					g.drawString("[↓]", colDn, y);
				}

				// token field
				{
					boolean focused = rowFocused && focusCol == 3;
					boolean valid = tokenValid(i);
					g.setPaint(!valid ? Color.RED : focused ? new Color(255, 255, 100) : Color.GRAY);
					g.drawString(row[0].toString(), colToken, y);
				}

				// steps field
				{
					boolean focused = rowFocused && focusCol == 4;
					boolean valid = stepsValid(i);
					g.setPaint(!valid ? Color.RED : focused ? new Color(255, 255, 100) : Color.GRAY);
					g.drawString(row[1].toString(), colSteps, y);
				}

				y += lineH + 4;
			}

			// Add entry button
			g.setPaint(focusRow == rows.size() ? Color.WHITE : Color.DARK_GRAY);
			g.drawString("[ Add Entry ]", x, y);
			y += lineH * 2;

			g.setPaint(new Color(70, 70, 70));
			g.drawString("UP/DOWN: rows   LEFT/RIGHT: field   ENTER: activate   ESC: back", x, y);
		}
	}

	class TextInputPanel extends JPanel implements InlineEntryPanel {
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
		}

		public void reset()            { buffer.setLength(0); repaint(); }
		public void reset(String text) { buffer.setLength(0); buffer.append(text); repaint(); }
		public String getText()        { return buffer.toString(); }

		private void append(char c) { buffer.append(c); mainInterfacePanel.repaint(); }
		private void backspace()    { if (buffer.length() > 0) { buffer.deleteCharAt(buffer.length()-1); mainInterfacePanel.repaint(); } }
		private void confirm()      { String t = buffer.toString(); reset(); onConfirm.accept(t); }

		// Inline mode entry points (called from MainInterfacePanel when activeInlinePanel == this)
		public void appendInline(char c) { buffer.append(c); }
		public void backspaceInline()    { if (buffer.length() > 0) buffer.deleteCharAt(buffer.length()-1); }

		public void inlineUp()    {}
		public void inlineDown()  {}
		public void inlineLeft()  {}
		public void inlineRight() {}
		public void inlineEnter() { confirm(); }
		public void paintInline(Graphics2D g, int x, int y, int w, int h) {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fillRect(x, y, w, h);
			int rowH = textFontMetrics.getMaxAscent();
			int drawY = y + rowH;
			int labelW = textFontMetrics.stringWidth(label);
			g.setPaint(Color.WHITE);
			g.drawString(label, x + 2, drawY);
			g.setPaint(new Color(255, 255, 100));
			g.drawString(" " + buffer.toString() + "|", x + 2 + labelW, drawY);
			drawY += rowH * 2;
			g.setPaint(Color.GRAY);
			g.drawString("Enter: confirm     Esc: cancel", x + 2, drawY);
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fill(getBounds());

			int rowH = textFontMetrics.getMaxAscent();
			int y = rowH;

			int labelW = textFontMetrics.stringWidth(label);
			g.setPaint(Color.WHITE);
			g.drawString(label, 2, y);
			g.setPaint(new Color(255, 255, 100));
			g.drawString(" " + buffer.toString() + "|", 2 + labelW, y);
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
		String mc = IS_MAC ? "Cmd" : "Ctrl";
		String ma = IS_MAC ? "Cmd" : "Alt";
		l.add(new Pair<>("GRID — NAVIGATION", HEADER_COLOR));
		l.add(new Pair<>("  Arrow keys              Move cursor", ENTRY_COLOR));
		l.add(new Pair<>("  Click                   Move cursor to cell", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " + Click             Move playback to cell", ENTRY_COLOR));
		l.add(new Pair<>("  Shift \u2190 / \u2192           Jump to prev / next measure", ENTRY_COLOR));
		l.add(new Pair<>("  Shift \u2191 / \u2193           Jump to first / last row", ENTRY_COLOR));
		l.add(new Pair<>("  Ctrl Shift \u2190 / \u2192      Jump to start / end of sequence", ENTRY_COLOR));
		l.add(new Pair<>("  " + ma + " \u2190 / \u2192              Move playback position 1 cell", ENTRY_COLOR));
		l.add(new Pair<>("  " + ma + " Shift \u2190 / \u2192        Jump playback by measure", ENTRY_COLOR));
		l.add(new Pair<>("  Home                    Jump to start", ENTRY_COLOR));
		l.add(new Pair<>("  End                     Jump to last event", ENTRY_COLOR));
		l.add(new Pair<>("  Page Up / Page Down     Scroll 80% of window", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("GRID — ENTRY", HEADER_COLOR));
		l.add(new Pair<>("  A-Z, 0-9                Enter note at cursor", ENTRY_COLOR));
		l.add(new Pair<>("  - (hyphen)              Insert sustain (string canvases)", ENTRY_COLOR));
		l.add(new Pair<>("  Backspace               Delete note at cursor", ENTRY_COLOR));
		l.add(new Pair<>("  Delete                  Clear cell", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("GRID — PLAYBACK", HEADER_COLOR));
		l.add(new Pair<>("  Space                   Play / Pause", ENTRY_COLOR));
		l.add(new Pair<>("  Shift + Space           Play from start", ENTRY_COLOR));
		l.add(new Pair<>("  Right-click             Set playback start (or paste if clipboard)", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("GRID — REPEAT MARKERS", HEADER_COLOR));
		l.add(new Pair<>("  " + mc + " [                   Set / clear repeat start (\u2590|)", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " ]                   Set / clear repeat end   (|\u258c)", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("GRID — SELECTION  (" + mc + "+L to begin)", HEADER_COLOR));
		l.add(new Pair<>("  " + mc + " + L               Start / end lasso selection", ENTRY_COLOR));
		l.add(new Pair<>("  Shift + drag            Lasso select by dragging", ENTRY_COLOR));
		l.add(new Pair<>("  Shift + drag, release        → copy to clipboard", ENTRY_COLOR));
		l.add(new Pair<>("  Shift + drag, " + mc + " + release  → cut to clipboard", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " + C               Copy selection", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " + X               Cut selection", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " + V               Paste", ENTRY_COLOR));
		l.add(new Pair<>("  Escape                  Cancel selection", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("GRID — UNDO / REDO", HEADER_COLOR));
		l.add(new Pair<>("  " + mc + " + Z               Undo", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " + R               Redo", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("GRID — TICK INSERTION / DELETION", HEADER_COLOR));
		l.add(new Pair<>("  " + mc + " + I               Insert empty tick at cursor (shift right)", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " + D               Delete tick at cursor (shift left)", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("GRID — SAVING & CANVAS EDITOR", HEADER_COLOR));
		l.add(new Pair<>("  " + mc + " + S               Save", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " + Shift + S       Save As", ENTRY_COLOR));
		l.add(new Pair<>("  " + mc + " + E               Open editor for current canvas", ENTRY_COLOR));
		l.add(new Pair<>("  , (comma)               Enter menu bar", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("INSTRUMENT / DRUM CANVAS — EDIT MODES", HEADER_COLOR));
		l.add(new Pair<>("  n                       Switch to NOTE mode (default)", ENTRY_COLOR));
		l.add(new Pair<>("  v                       Switch to VELOCITY mode", ENTRY_COLOR));
		l.add(new Pair<>("  p                       Switch to PAN mode", ENTRY_COLOR));
		l.add(new Pair<>("  o                       Switch to OFFSET mode", ENTRY_COLOR));
		l.add(new Pair<>("  Velocity / Pan entry:   type two hex digits (00–7F)", ENTRY_COLOR));
		l.add(new Pair<>("  Alt \u2191 / \u2193              Nudge vel / pan / offset by 1", ENTRY_COLOR));
		l.add(new Pair<>("  \u2190 / \u2192 (vel/pan/offset)  Skip to prev/next note with a value (wraps)", ENTRY_COLOR));
		l.add(new Pair<>("  \u2191 / \u2193 (vel/pan/offset)  Switch rows, snap to nearest valued cell", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("EVENT CANVAS — SPECIAL KEYS", HEADER_COLOR));
		l.add(new Pair<>("  T                       Insert time signature", ENTRY_COLOR));
		l.add(new Pair<>("  S                       Insert tempo change", ENTRY_COLOR));
		l.add(new Pair<>("  F                       Insert shuffle change", ENTRY_COLOR));
		l.add(new Pair<>("  N                       Insert note event", ENTRY_COLOR));
		l.add(new Pair<>("  P                       Insert program change", ENTRY_COLOR));
		l.add(new Pair<>("  U                       Insert tuning change", ENTRY_COLOR));
		l.add(new Pair<>("  Q                       Enter tuplet selection mode", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("TUPLET MODE  (after pressing Q on event canvas)", HEADER_COLOR));
		l.add(new Pair<>("  \u2190 / \u2192                  Extend / shrink tuplet range", ENTRY_COLOR));
		l.add(new Pair<>("  Enter                   Confirm and open tuplet editor", ENTRY_COLOR));
		l.add(new Pair<>("  Escape                  Cancel", ENTRY_COLOR));
		l.add(new Pair<>("", ENTRY_COLOR));
		l.add(new Pair<>("MENU BAR  (press , from grid)", HEADER_COLOR));
		l.add(new Pair<>("  \u2190 / \u2192                  Navigate items", ENTRY_COLOR));
		l.add(new Pair<>("  \u2191 / \u2193                  Adjust TEMPO or SHUFFLE value", ENTRY_COLOR));
		l.add(new Pair<>("  Enter                   Activate selected item", ENTRY_COLOR));
		l.add(new Pair<>("  Space                   Play / Pause", ENTRY_COLOR));
		l.add(new Pair<>("  , (comma)               Return to grid", ENTRY_COLOR));
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

	class EventCanvasSettingsPanel extends JPanel {
		private EventCanvasConfig targetCanvas = null;
		private final IntSpinner numRowsSpinner = new IntSpinner("Rows", 3, 1, 16);
		private final StringBuffer nameBuffer = new StringBuffer();

		public EventCanvasSettingsPanel() {
			InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap am = getActionMap();
			im.put(k_Escape, "ecs_esc");
			am.put("ecs_esc", rToA(() -> cardLayout.show(cardPanel, mainInterfaceCardKey)));
			im.put(k_Up,    "ecs_up");   am.put("ecs_up",   rToA(this::up));
			im.put(k_Down,  "ecs_down"); am.put("ecs_down", rToA(this::down));
			im.put(k_Backspace, "ecs_bs");
			am.put("ecs_bs", rToA(() -> { if (nameBuffer.length() > 0) { nameBuffer.deleteCharAt(nameBuffer.length()-1); repaint(); } }));
			im.put(k_Enter, "ecs_enter");
			am.put("ecs_enter", rToA(this::commitName));
		}

		void prepare(EventCanvasConfig canvas) {
			targetCanvas = canvas;
			numRowsSpinner.setValue(canvas.getNumRows());
			nameBuffer.setLength(0);
			nameBuffer.append(canvas.getName());
		}

		void appendToName(char c) { nameBuffer.append(c); repaint(); }

		void commitName() {
			String newName = nameBuffer.toString().trim();
			if (!newName.isEmpty() && targetCanvas != null) {
				targetCanvas.setName(newName);
			}
			cardLayout.show(cardPanel, mainInterfaceCardKey);
			mainInterfacePanel.repaint();
		}

		void up() {
			numRowsSpinner.up();
			if (targetCanvas != null) targetCanvas.setNumRows(numRowsSpinner.getValue());
			if (projectData != null) updateMeasureLinePositions();
			repaint();
			mainInterfacePanel.repaint();
		}

		void down() {
			numRowsSpinner.down();
			if (targetCanvas != null) targetCanvas.setNumRows(numRowsSpinner.getValue());
			if (projectData != null) updateMeasureLinePositions();
			repaint();
			mainInterfacePanel.repaint();
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fill(getBounds());
			int lh = textFontMetrics.getMaxAscent();
			g.setPaint(Color.WHITE);
			g.drawString((targetCanvas != null ? targetCanvas.getName() : "Event Canvas") + " Settings", 4, lh);
			g.setPaint(new Color(80, 80, 80));
			g.drawString("Name:", 4, lh * 3);
			g.setPaint(new Color(255, 255, 100));
			g.drawString(nameBuffer.toString() + "|", 4 + textFontMetrics.stringWidth("Name: "), lh * 3);
			numRowsSpinner.paint(g, 4, lh * 5, true);
			g.setPaint(new Color(70, 70, 70));
			g.drawString("ENTER: confirm name   UP/DOWN: change rows   ESC: back", 4, lh * 7);
		}
	}

	class DeleteConfirmPanel extends JPanel {
		private int targetIndex = -1;
		private String targetName = "";
		private String targetId   = "";
		private int selection = 1; // 0 = Yes, 1 = No (default No)

		public DeleteConfirmPanel() {
			InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap am = getActionMap();
			im.put(k_Escape, "dc_esc");
			am.put("dc_esc", rToA(this::cancel));
			im.put(k_Left,  "dc_left");  am.put("dc_left",  rToA(this::left));
			im.put(k_Right, "dc_right"); am.put("dc_right", rToA(this::right));
			im.put(k_Enter, "dc_enter"); am.put("dc_enter", rToA(this::enter));
		}

		void prepare(int index, String name, String id) {
			targetIndex = index;
			targetName  = name;
			targetId    = id;
			selection   = 1; // default No
		}

		void cancel() { cardLayout.show(cardPanel, mainInterfaceCardKey); }
		void left()   { selection = 0; repaint(); }
		void right()  { selection = 1; repaint(); }

		void enter() {
			if (selection == 0 && targetIndex >= 0 && projectData != null) {
				// Delete canvas
				projectData.getCanvases().removeCanvas(targetIndex);
				// Remove instrument data for this canvas (keyed by canvas id)
				projectData.getInstrumentData().entrySet().removeIf(
						e -> e.getKey().getInstrumentId().equals(targetId));
				// Remove event data for this canvas
				projectData.removeEventData(targetId);
				// Clamp selectedRow
				int maxRow = projectData.getCanvases().getCanvases().stream()
						.mapToInt(CanvasConfig::getRowCount).sum();
				if (projectData.getSelectedRow().get() >= maxRow) {
					projectData.getSelectedRow().set(0);
				}
				updateMeasureLinePositions();
			}
			cardLayout.show(cardPanel, mainInterfaceCardKey);
			mainInterfacePanel.repaint();
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fill(getBounds());
			int lh = textFontMetrics.getMaxAscent() + 2;
			g.setPaint(Color.WHITE);
			g.drawString("Delete \"" + targetName + "\"?", 4, lh);
			g.setPaint(new Color(200, 80, 80));
			g.drawString("All note data for this canvas will be lost.", 4, lh * 2);
			int yBtn = lh * 4;
			g.setPaint(selection == 0 ? Color.WHITE : Color.DARK_GRAY);
			g.drawString("[ Yes ]", 4, yBtn);
			g.setPaint(selection == 1 ? Color.WHITE : Color.DARK_GRAY);
			g.drawString("[ No ]", 80, yBtn);
		}
	}

	class NewCanvasPanel extends JPanel {
		private List<CanvasConfig> templates = new java.util.ArrayList<>();
		private int selectedIndex = 0;

		public NewCanvasPanel() {
			InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap am = getActionMap();
			im.put(k_Escape, "ncp_esc");
			am.put("ncp_esc", rToA(() -> cardLayout.show(cardPanel, mainInterfaceCardKey)));
			im.put(k_Up,    "ncp_up");    am.put("ncp_up",    rToA(this::up));
			im.put(k_Down,  "ncp_down");  am.put("ncp_down",  rToA(this::down));
			im.put(k_Enter, "ncp_enter"); am.put("ncp_enter", rToA(this::enter));
		}

		void prepare() {
			templates.clear();
			// Load templates from canvasesConfig (the global template list from etc/canvases.xml)
			templates.addAll(canvasesConfig.getCanvases());
			// Add a blank event canvas option
			templates.add(new EventCanvasConfig("Events", 3));
			selectedIndex = 0;
			repaint();
		}

		void up()   { if (selectedIndex > 0) selectedIndex--;            repaint(); }
		void down() { if (selectedIndex < templates.size() - 1) selectedIndex++; repaint(); }

		void enter() {
			if (projectData == null || selectedIndex < 0 || selectedIndex >= templates.size()) {
				cardLayout.show(cardPanel, mainInterfaceCardKey);
				return;
			}
			CanvasConfig template = templates.get(selectedIndex);
			// Clone the template with a unique name
			CanvasConfig newCanvas;
			if (template instanceof EventCanvasConfig) {
				newCanvas = new EventCanvasConfig("Events " + (projectData.getCanvases().getCanvases().size() + 1), ((EventCanvasConfig) template).getNumRows());
			} else if (template instanceof DrumCanvasConfig) {
				// Re-parse via XML round-trip to get a deep clone
				try {
					javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
					org.w3c.dom.Document doc = db.newDocument();
					org.w3c.dom.Element el = template.toXMLElement(doc, "drumCanvas");
					newCanvas = DrumCanvasConfig.fromXMLElement(el);
					newCanvas.regenerateId();
				} catch (Exception ex) { cardLayout.show(cardPanel, mainInterfaceCardKey); return; }
			} else if (template instanceof StringCanvasConfig) {
				try {
					javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
					org.w3c.dom.Document doc = db.newDocument();
					org.w3c.dom.Element el = template.toXMLElement(doc, "stringCanvas");
					newCanvas = StringCanvasConfig.fromXMLElement(el);
					newCanvas.regenerateId();
				} catch (Exception ex) { cardLayout.show(cardPanel, mainInterfaceCardKey); return; }
			} else {
				cardLayout.show(cardPanel, mainInterfaceCardKey);
				return;
			}
			projectData.getCanvases().getCanvases().add(newCanvas);
			updateMeasureLinePositions();
			// Open editor for new canvas if instrument/drum
			cardLayout.show(cardPanel, mainInterfaceCardKey);
			int newCanvasIndex = projectData.getCanvases().getCanvases().size() - 1;
			// Set cursor to the new canvas
			int newRow = projectData.getCanvases().getCanvases().stream()
					.limit(newCanvasIndex).mapToInt(CanvasConfig::getRowCount).sum();
			projectData.getSelectedRow().set(newRow);
			mainInterfacePanel.repaint();
			// Open its editor
			if (!(newCanvas instanceof EventCanvasConfig)) {
				mainInterfacePanel.ctrlI();
			}
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(textFont);
			g.setPaint(Color.BLACK);
			g.fill(getBounds());
			int lh = textFontMetrics.getMaxAscent() + 2;
			g.setPaint(Color.WHITE);
			g.drawString("Add Canvas — choose template:", 4, lh);
			for (int i = 0; i < templates.size(); i++) {
				CanvasConfig tc = templates.get(i);
				String type = (tc instanceof EventCanvasConfig) ? "[Event]"
						: (tc instanceof DrumCanvasConfig) ? "[Drum]" : "[String]";
				String label = type + " " + tc.getName();
				g.setPaint(i == selectedIndex ? Color.WHITE : new Color(100, 100, 100));
				g.drawString((i == selectedIndex ? "> " : "  ") + label, 4, lh * (i + 3));
			}
			g.setPaint(new Color(70, 70, 70));
			g.drawString("UP/DOWN: select   ENTER: add   ESC: cancel", 4, lh * (templates.size() + 4));
		}
	}
}
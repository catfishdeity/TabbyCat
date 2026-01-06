package tabsequencer;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import tabsequencer.events.TempoEvent;
import tabsequencer.events.TimeSignatureDenominator;
import tabsequencer.events.TimeSignatureEvent;

public class TabbyCat {

	public static void main(String[] args) {
		TabbyCat.getInstance();
	}

	private static TabbyCat instance;

	static final String loadProjectCardKey = "LOAD PROJECT";
	static final String newProjectCardKey = "NEW PROJECT";
	static final String mainInterfaceCardKey = "MAIN INTERFACE";
	static final String saveProjectCardKey = "SAVE PROJECT";
	static final String timeSignatureEventCardKey = "TIME SIGNATURE";
	static final String tempoEventCardKey = "TEMPO EVENT";
	static final String notesEventCardKey = "NEW NOTE";;
	
	static final double MIDDLE_C = 220.0 * Math.pow(2d, 3.0 / 12.0);	
	static final int numEventRows = 3;
	
	private ProjectFileData projectData = null;
	private CanvasesConfig canvasesConfig = CanvasesConfig.getXMLInstance();
	private Font gridFont = new Font("Monospaced", Font.BOLD, 10);
	private FontMetrics gridFontMetrics = new Canvas().getFontMetrics(gridFont);
	private Font textFont = new Font("Monospaced", Font.PLAIN, 14);
	private FontMetrics textFontMetrics = new Canvas().getFontMetrics(textFont);
	
	final int scrollTimeMargin = 4;	

	FileFilter fileFilter = new FileNameExtensionFilter(".meow files (.meow)", "meow");
	final File defaultProjectPath = new File("scores");	
	
	Map<File,Soundbank> loadedSoundbanks = new HashMap<>();
	Map<DrumCanvasConfig,Synthesizer> drumSynths = new HashMap<>();
	Map<Pair<StringCanvasConfig,Integer>,Synthesizer> stringSynths = new HashMap<>();
	
	final AtomicReference<File> activeFile = new AtomicReference<>(null);
	final AtomicBoolean fileHasBeenModified = new AtomicBoolean(false);
		
	final AtomicBoolean isPlaying = new AtomicBoolean(false);	
	final AtomicBoolean playbackDaemonIsStarted = new AtomicBoolean(false);

	final TreeMap<Integer, Integer> cachedMeasurePositions = new TreeMap<>();
	final HashSet<Integer> cachedBeatMarkerPositions = new HashSet<>();

	final ScheduledExecutorService playbackDaemon = Executors.newSingleThreadScheduledExecutor();
	final ScheduledExecutorService midiDaemon = Executors.newSingleThreadScheduledExecutor();

	final JFrame frame = new JFrame("TabbyCat");
		
	File defaultSoundfontFile = null;

	private TimeSignatureEventPanel timeSignatureEventPanel;
	private TempoEventPanel tempoEventPanel;
	private NotesEventPanel notesEventPanel;
	private LoadProjectPanel loadProjectPanel;
	private NewProjectPanel newProjectPanel;
	private MainInterfacePanel mainInterfacePanel;
	private SaveProjectPanel saveProjectPanel;
	
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
	
	KeyStroke k_CtrlUp = KeyStroke.getKeyStroke("ctrl UP");
	KeyStroke k_CtrlDown = KeyStroke.getKeyStroke("ctrl DOWN");
	KeyStroke k_CtrlLeft = KeyStroke.getKeyStroke("ctrl LEFT");
	KeyStroke k_CtrlRight = KeyStroke.getKeyStroke("ctrl RIGHT");
	
	KeyStroke k_Enter = KeyStroke.getKeyStroke("ENTER");
	KeyStroke k_Backspace = KeyStroke.getKeyStroke("BACK_SPACE");
	KeyStroke k_Comma = KeyStroke.getKeyStroke("COMMA");
	KeyStroke k_Hyphen= KeyStroke.getKeyStroke('-');
	
	KeyStroke k_CtrlL = KeyStroke.getKeyStroke("ctrl L");
	KeyStroke k_CtrlC = KeyStroke.getKeyStroke("ctrl C");
	KeyStroke k_CtrlV = KeyStroke.getKeyStroke("ctrl V");
	KeyStroke k_CtrlX = KeyStroke.getKeyStroke("ctrl X");
	KeyStroke k_CtrlR = KeyStroke.getKeyStroke("ctrl R");
	KeyStroke k_CtrlS = KeyStroke.getKeyStroke("ctrl S");
	
	KeyStroke k_Space = KeyStroke.getKeyStroke("SPACE");

	

	void playbackDaemonFunction() {
		// DO NOT CALL THIS ON MASTER THREAD
		while (true) {
			if (!isPlaying.get()) {
				continue;
			} else {				
				try {
					if (projectData.getPlaybackT().get() == 0) {
						projectData.getTempo().set(projectData.getInitialTempo().get());
					}
					midiDaemon.execute(() -> {
						handleProgramEvents();
					});

					SwingUtilities.invokeAndWait(() -> {
						mainInterfacePanel.repaint();
					});

				} catch (Exception e) {
					e.printStackTrace();
				}

				projectData.getPlaybackT().getAndUpdate(
						i -> i + 1 == projectData.getRepeatT().get() ? projectData.getPlaybackStartT().get() : i + 1);
				long bpm = projectData.getTempo().get();
				Duration sixteenth = Duration.ofMinutes(1).dividedBy(bpm).dividedBy(4);
				playbackDaemon.schedule(() -> playbackDaemonFunction(), sixteenth.toMillis(), TimeUnit.MILLISECONDS);

				return;
			}
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
	
	Synthesizer getSynth(StringCanvasConfig config, int row) throws Exception {
		
		Pair<StringCanvasConfig,Integer> key = new Pair<>(config,row);
		if (stringSynths.containsKey(key)) {
			return stringSynths.get(key);
		} else {
			Synthesizer synth = MidiSystem.getSynthesizer();
			if (!synth.isOpen()) {
				synth.open();
			}			
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
			Synthesizer synth = MidiSystem.getSynthesizer();
			if (!synth.isOpen()) {
				synth.open();
			}			
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
		StringBuilder sb = new StringBuilder();
		sb.append("KiteTabSequencer2000");
		if (activeFile.get() == null) {
			if (fileHasBeenModified.get()) {
				sb.append(" (unsaved)");
			}
		} else {
			sb.append(" (");
			sb.append(activeFile.get().getName());
			if (fileHasBeenModified.get()) {
				sb.append(" * ");
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
	

	void startPlayback() {
		if (!playbackDaemonIsStarted.get()) {
			playbackDaemonIsStarted.set(true);
			playbackDaemon.schedule(() -> playbackDaemonFunction(), 0, TimeUnit.SECONDS);
		}
		isPlaying.set(true);
	}

	void stopPlayback() {
		isPlaying.set(false);		
	}
	
	public void updateMeasureLinePositions() {
		AtomicReference<TimeSignatureEvent> timeSignature = new AtomicReference<>(
				new TimeSignatureEvent(4, TimeSignatureDenominator._4));
		AtomicInteger counter = new AtomicInteger(0);
		AtomicInteger measure = new AtomicInteger(1);
		Map<Integer, Integer> measures = new TreeMap<>();
		Set<Integer> markers = new HashSet<>();
		measures.put(0, measure.getAndIncrement());
		for (int t = 0; t < 1000 * 16; t++) {
			int t_ = t;
			Optional<TimeSignatureEvent> tsO = IntStream.range(0, 3)
					.mapToObj(row -> new Point(t_, row)).filter(a -> projectData.getEventData().containsKey(a))
					.map(projectData.getEventData()::get).filter(a -> a.getType() == ControlEventType.TIME_SIGNATURE)
					.map(a -> (TimeSignatureEvent) a).findFirst();

			if (tsO.isPresent()) {
				timeSignature.set(tsO.get());
				counter.set(0);
				measures.put(t, measure.get());
				if (t > 0) {
					measure.getAndIncrement();

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
		notesEventPanel = new NotesEventPanel();
		cardPanel.add(notesEventPanel,notesEventCardKey);
		frame.getContentPane().add(cardPanel,BorderLayout.CENTER);
		frame.pack();
		frame.setSize(new Dimension(
				(int) Math.min(1000, Toolkit.getDefaultToolkit().getScreenSize().getWidth()),
				(int) Math.min(500, Toolkit.getDefaultToolkit().getScreenSize().getHeight())));
				
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}
	

	void loadXML(File file) throws Exception {
		

		SchemaFactory schemaF = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
		Schema schema = schemaF.newSchema(new File("schemas/projectFiles.xsd"));
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		dbf.setSchema(schema);
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(file);
		ProjectFileData projectFileData = ProjectFileData.fromXMLElement(doc.getDocumentElement());
		this.projectData = projectFileData;				
		updateMeasureLinePositions();
		for (CanvasConfig canvasConfig : projectData.getCanvases().getCanvases()) {
			if (canvasConfig instanceof StringCanvasConfig ) {
				StringCanvasConfig a = (StringCanvasConfig) canvasConfig;
				for (int row = 0; row < a.getRowCount(); row++) {
					getSynth(a,row);
				}
			} else {
				getSynth((DrumCanvasConfig)canvasConfig);
			}
		}
		
	}

	
	void saveXML(File file) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.newDocument();
		Element root = projectData.toXMLElement(doc);
		doc.appendChild(root);
		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer t  = tf.newTransformer();
		t.setOutputProperty(OutputKeys.INDENT,"yes");
		t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount","4");
		t.transform(new DOMSource(doc), new StreamResult(file));
		t.transform(new DOMSource(doc), new StreamResult(System.out));
		
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
					}).sorted(cmp2.reversed().thenComparing(cmp1)).toList();
			
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
							.sorted(cmp1.thenComparing(cmp2)).map(a->a.a).toList());
				projectData = new ProjectFileData(config);
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
			g.drawString(songName.toString(),textFieldX,y);
			g.setClip(null);
			y+=rowHeight;
			g.setPaint(Color.WHITE);
			g.drawString(artistLabel,2,y);
			g.setPaint(selectedIndex==1?Color.LIGHT_GRAY:Color.GRAY);
			g.fillRect(textFieldX,y-rowHeight,textFieldWidth,rowHeight);
			g.setPaint(Color.WHITE);
			g.setClip(new Rectangle2D.Double(textFieldX,y-rowHeight,textFieldWidth,rowHeight));
			g.drawString(artistName.toString(),textFieldX,y);
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
				selectedIndex = 1+numFiles;				
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
			if (selectedIndex == 2+numFiles) {
				selectedIndex = 0;
			}
			repaint();
			
		}
		
		private void enter() {
			if (selectedIndex == 0) {
				cardLayout.show(cardPanel, newProjectCardKey);
			} else if (selectedIndex == 1) {
				this.workingDir = new File(workingDir.getAbsolutePath()).getParentFile();
				repaint();
			} else {
				List<File> files = 
						Arrays.asList(workingDir.listFiles()).stream().filter(a->a.isDirectory() || fileFilter.accept(a))
						.toList();
				if (files.get(selectedIndex-2).isDirectory()) {
					workingDir = new File(workingDir.getAbsolutePath()+"/"+files.get(selectedIndex-2).getName());
					selectedIndex= 1;
					repaint();
				} else {

					try {
						loadXML(files.get(selectedIndex-2));
						activeFile.set(files.get(selectedIndex-2));
					} catch (Exception e) {

						e.printStackTrace();
					} finally {
						cardLayout.show(cardPanel, mainInterfaceCardKey);
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
			strings.add(new Pair<>("<New Project>",new Color(180,180,255)));
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
	
	class MainInterfacePanel extends JPanel {
		
		enum SequencePosition {
			SAVE, LOAD, TEMPO, TAPPER, SETTINGS;  
		}
		
		SequencePosition sequencePosition = SequencePosition.TAPPER;		
		boolean isInGrid = false;
		AtomicBoolean isSelectionMode = new AtomicBoolean(false);
		
		final Map<Point, String> instrumentClipboard = new HashMap<>();
		final Map<Point, ControlEvent> eventClipboard = new HashMap<>();
		
		int lassoCanvasNumber = -1;
		int lassoT0 = -1;
		int lassoRow0 = -1;
		
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
			
			inputMap.put(k_CtrlUp,"ctrlup");
			actionMap.put("ctrlup", rToA(this::ctrlUp));
			inputMap.put(k_CtrlDown,"ctrldown");
			actionMap.put("ctrldown", rToA(this::ctrlDown));
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
				saveProjectPanel.setFileName(
							String.format("%s.meow",
									DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(LocalDateTime.now(ZoneId.of("Z")))));
				cardLayout.show(cardPanel, saveProjectCardKey);
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
		
		void backspace() {
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
					System.out.println(projectData.getInstrumentData());
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
				
			}
			
			isSelectionMode.set(false);
		}
		
		void ctrlX() {
			instrumentClipboard.clear();
			eventClipboard.clear();

			if (isSelectionMode.get()) {
				
			}

			isSelectionMode.set(false);
		}
		
		void ctrlV() {
			
		}
		
		public void toggleSelectionMode() {
			
			
			isSelectionMode.set(!isSelectionMode.get());
			if (isSelectionMode.get()) {
				Pair<Integer,Integer> p = getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
				this.lassoCanvasNumber = p.a;
				this.lassoT0 = projectData.getCursorT().get();
				this.lassoRow0 = p.b;

				
			} else {
				this.lassoCanvasNumber = -1;
				this.lassoT0 = -1;
				this.lassoRow0 = -1;
								
			}
			repaint();
		
		}
		void ctrlUp() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.CTRL_UP);
			}
		}
		
		void ctrlDown() {
			if (isInGrid) {
				handleGridMovement(CardinalDirection.CTRL_DOWN);
			}
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
		
		public final int getMaxVisibleTime() {
			int t0 = projectData.getViewT().get();
			
			double tDelta = (getWidth() / getCellWidth());
			double t1 = t0 + tDelta;		
			return (int) t1;
		}
		
		public void cursorTToPrevMeasure() {
			NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(projectData.getCursorT().get(), false);
			if (!headMap.isEmpty()) {
				Entry<Integer, Integer> last = headMap.lastEntry();
				projectData.getCursorT().set(last.getKey());
			}
			while (projectData.getCursorT().get() < projectData.getViewT().get() + scrollTimeMargin) {
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
			while (projectData.getPlaybackT().get() < projectData.getViewT().get() + scrollTimeMargin) {
				projectData.getViewT().getAndDecrement();
			}
			
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
			
		}
		
		enum CardinalDirection {
			RIGHT, LEFT, UP, DOWN, 
			SHIFT_RIGHT, SHIFT_LEFT, SHIFT_UP, SHIFT_DOWN,
			CTRL_RIGHT, CTRL_LEFT, CTRL_UP, CTRL_DOWN,
			CTRL_SHIFT_LEFT, CTRL_SHIFT_RIGHT, CTRL_SHIFT_UP, CTRL_SHIFT_DOWN;
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
				if (projectData.getCursorT().get() < projectData.getViewT().get() + scrollTimeMargin) {
					projectData.getViewT().getAndDecrement();
				}
				repaint();
				break;
			case RIGHT:
				projectData.getCursorT().incrementAndGet();
				System.out.printf("%s %s\n", projectData.getCursorT().get(), getMaxVisibleTime());
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
				break;
			case SHIFT_DOWN:				
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
				projectData.getViewT().set(-4);
				repaint();
				break;
			case CTRL_SHIFT_RIGHT:
				this.advanceCursorToFinalEvent();
				repaint();				
				break;
			case CTRL_DOWN:
				break;
			case CTRL_LEFT:
				decrementPlayT();
				repaint();
				break;
			case CTRL_RIGHT:
				incrementPlayT();
				repaint();
				break;
			case CTRL_UP:
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
						.toList();
				double secs = durations.stream().mapToLong(a->a.toNanos()).average().getAsDouble()
						/1000000000d;
						
				projectData.getTempo().set((int) (60.0/secs));
				//projectData.getTempo().set(110);//(int) bpm);
				
			}
		}
		void enter() {
			switch (sequencePosition) {
			case LOAD:
				
				break;
			case SAVE:				
				mainInterfacePanel.ctrlS();
				break;
			case SETTINGS:
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

		}
		

		public void incrementPlayT() {
			projectData.getPlaybackT().incrementAndGet();
			while (projectData.getPlaybackT().get() > getMaxVisibleTime() - scrollTimeMargin) {
				projectData.getViewT().getAndIncrement();
			}
			
		}

		public void decrementPlayT() {
			projectData.getPlaybackT().getAndUpdate(i -> Math.max(0, i - 1));
			while (projectData.getPlaybackT().get() < projectData.getViewT().get() + scrollTimeMargin) {
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
			Font gridFont = new Font("Monospaced",Font.BOLD,12);			
			FontMetrics gridMetrics = g.getFontMetrics(gridFont);
			Font topFont = new Font("SansSerif",Font.PLAIN,14);			
			FontMetrics topFontMetrics = g.getFontMetrics(topFont);			
			g.setFont(topFont);
			int topBarHeight = topFontMetrics.getMaxAscent();
			Iterator<Double> hueIterator = DoubleStream.iterate(0f, i->i+0.07).iterator();
			Runnable iterateHue = () -> {
				g.setPaint(Color.getHSBColor(hueIterator.next().floatValue(),0.5f,1f));
			};
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
			g.setPaint(Color.WHITE);
						
			
			AffineTransform at = new AffineTransform();
			at.translate(0, topBarHeight);
			Rectangle2D saveBounds = topFontMetrics.getStringBounds("SAVE", g);			
			saveBounds = at.createTransformedShape(saveBounds).getBounds2D();
			at.translate(saveBounds.getWidth()+5,0);
			//g.draw(saveBounds);
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.SAVE?Color.GRAY:Color.BLACK);
			g.fill(saveBounds);
			iterateHue.run();
			g.drawString("SAVE",(int) saveBounds.getMinX(),(int) saveBounds.getMaxY());
			iterateHue.run();
			Rectangle2D loadBounds = topFontMetrics.getStringBounds("LOAD", g);
			loadBounds = at.createTransformedShape(loadBounds).getBounds2D();
			
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.LOAD?Color.GRAY:Color.BLACK);
			g.fill(loadBounds);
			iterateHue.run();
			g.drawString("LOAD",(int) loadBounds.getMinX(),(int) loadBounds.getMaxY());
			at.translate(loadBounds.getWidth()+5,0);
			String tempoString = String.format("TEMPO %03d",projectData.getTempo().get());
			Rectangle2D tempoBounds = topFontMetrics.getStringBounds(tempoString, g);
			tempoBounds = at.createTransformedShape(tempoBounds).getBounds2D();
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.TEMPO?Color.GRAY:Color.BLACK);
			g.fill(tempoBounds);
			iterateHue.run();
			g.drawString(tempoString,(int) tempoBounds.getMinX(),(int) tempoBounds.getMaxY());
			at.translate(tempoBounds.getWidth()+5,0);			
			String tapString = "TAP!";
			Rectangle2D tapBounds = topFontMetrics.getStringBounds(tapString, g);
			tapBounds = at.createTransformedShape(tapBounds).getBounds2D();
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.TAPPER?Color.GRAY:Color.BLACK);
			g.fill(tapBounds);
			iterateHue.run();
			g.drawString(tapString,(int) tapBounds.getMinX(),(int) tapBounds.getMaxY());
			at.translate(tapBounds.getWidth()+5,0);
			String settingsString = "SETTINGS";
			Rectangle2D settingsBounds = topFontMetrics.getStringBounds(settingsString, g);
			settingsBounds = at.createTransformedShape(settingsBounds).getBounds2D();
			g.setPaint(!isInGrid && sequencePosition==SequencePosition.SETTINGS?Color.GRAY:Color.BLACK);
			g.fill(settingsBounds);
			iterateHue.run();
			g.drawString(settingsString,(int) settingsBounds.getMinX(),(int) settingsBounds.getMaxY());
			//at.translate(settingsBounds.getWidth()+5,0);
			
			at.setToIdentity();
			
			at.translate(0, topBarHeight*2+5);//this might need to be changed if we exceed the size of the window
			
			g.setPaint(Color.WHITE);
			g.setFont(gridFont);
			g.drawLine(0,topBarHeight+5,getWidth(),topBarHeight+5);
			Area clip = new Area(getBounds());
			clip.subtract(new Area(new Rectangle2D.Double(0,0,getWidth(),topBarHeight+5)));
			g.setClip(clip);
			List<Shape> canvasGrids= new ArrayList<>();
			Map<String,Point2D> stringPositions = new HashMap<>();
			List<Rectangle2D> measurePanels = new ArrayList<>();
			Path2D.Double eventP2D = new Path2D.Double();
			int cellWidth = (int) getCellWidth();
			int rowHeight = gridMetrics.getMaxAscent()+4;
			int t0 = projectData.getViewT().get();
			int tDelta = (int) (getWidth()/cellWidth);
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
						new Point2D.Double(getWidth(),y)),false);
			}
			
			String eventsString = "Events";
			Rectangle2D eventsStringBounds = topFontMetrics.getStringBounds(eventsString, g);
			eventsStringBounds = at.createTransformedShape(eventsStringBounds).getBounds2D();

			stringPositions.put(eventsString,new Point2D.Double(eventsStringBounds.getMinX(),eventsStringBounds.getMaxY())); 			

			at.translate(0,rowHeight);


			canvasGrids.add(at.createTransformedShape(eventP2D));			
			at.translate(0,eventP2D.getBounds2D().getHeight());
			
			Rectangle2D eventMeasuresPanel = new Rectangle2D.Double(0, 0, getWidth(), rowHeight);
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
							new Point2D.Double(getWidth(),y)),false);
				}

				canvasGrids.add(at.createTransformedShape(p2d));			
				at.translate(0,p2d.getBounds2D().getHeight());
				Rectangle2D measuresPanel = new Rectangle2D.Double(0, 0, getWidth(), rowHeight);
				measurePanels.add(at.createTransformedShape(measuresPanel).getBounds2D());
				at.translate(0,measuresPanel.getHeight());
				
			}
			
			
			
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
						
			g.translate(0,Math.min(0,getBounds().getMaxY()-selectionRectangle.getMaxY()-rowHeight*2));
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
								case STICKY_NOTE: {
									g.setFont(gridFont.deriveFont(Font.ITALIC));
									g.setPaint(Color.BLUE);
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
					ex.printStackTrace();					
				}
				activeFile.set(f);
				fileHasBeenModified.set(false);
				updateWindowTitle();
				cardLayout.show(cardPanel, mainInterfaceCardKey);
				//cardLayout.show(cardPanel, newProjectCardKey);
			} else if (selectedIndex == 1) {
				this.workingDir = new File(workingDir.getAbsolutePath()).getParentFile();
				repaint();
			} else {
				List<File> files = 
						Arrays.asList(workingDir.listFiles()).stream().filter(a->a.isDirectory() || fileFilter.accept(a))
						.toList();
				if (files.get(selectedIndex-2).isDirectory()) {
					workingDir = new File(workingDir.getAbsolutePath()+"/"+files.get(selectedIndex-2).getName());
					selectedIndex= 1;
					repaint();
				} else {
					File f = new File(workingDir.getAbsolutePath()+"/"+fileName.toString());
					try {
						saveXML(f);
					} catch (Exception ex) {
						ex.printStackTrace();					
					}					
					activeFile.set(f);
					fileHasBeenModified.set(false);
					updateWindowTitle();
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
	
	class NotesEventPanel extends JPanel {
		private StringBuffer note = new StringBuffer();
		public NotesEventPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
			inputMap.put(k_Backspace,"backspace");
			actionMap.put("backspace", rToA(this::backspace));
			for (char c = 'A'; c <= 'Z'; c++) {
				char c_ = c;
				KeyStroke k = KeyStroke.getKeyStroke(""+c);
				inputMap.put(k,"shift "+c);
				actionMap.put(""+c, rToA(()->handleCharInput(c_)));
				k = KeyStroke.getKeyStroke(""+c);
				inputMap.put(k,""+c);
				actionMap.put(""+c, rToA(()->handleCharInput((""+c_).toLowerCase().charAt(0))));
			}
			for (char c = '0'; c <= '9'; c++) {
				char c_ = c;
				KeyStroke k = KeyStroke.getKeyStroke(""+c);
				inputMap.put(k,""+c);
				actionMap.put(""+c, rToA(()->handleCharInput(c_)));
			}		
		}
		
		
		void backspace() {
			note.deleteCharAt(note.length()-1);
			repaint();
		}
		
		void enter() {
			StickyNote stickyNote = new StickyNote(note.toString());
			projectData.getEventData().put(
					new Point(projectData.getCursorT().get(),projectData.getSelectedRow().get()),
					stickyNote);
									
			cardLayout.show(cardPanel, mainInterfaceCardKey);
			note.delete(0, note.length()-1);
		}
		
		void handleCharInput(char c) {
			note.append(c);
			repaint();
		}
		

		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.black);
			g.fill(getBounds());
			g.setPaint(Color.WHITE);
			g.setFont(textFont);
			g.drawString(note.toString(), 2, textFontMetrics.getMaxAscent());
			
		}
	}
}
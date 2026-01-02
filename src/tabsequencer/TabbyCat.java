package tabsequencer;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dialog.ModalityType;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.time.Duration;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerNumberModel;
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
import tabsequencer.config.PercToken;
import tabsequencer.config.ProjectFileData;
import tabsequencer.config.StringCanvasConfig;
import tabsequencer.events.ControlEvent;
import tabsequencer.events.ControlEventType;
import tabsequencer.events.ProgramChange;
import tabsequencer.events.StickyNote;
import tabsequencer.events.TempoEvent;
import tabsequencer.events.TimeSignatureDenominator;
import tabsequencer.events.TimeSignatureEvent;

public class TabbyCat {

	public static void main(String[] args) {
		TabbyCat.getInstance();
	}

	private static TabbyCat instance;

	static String loadProjectCardKey = "LOAD PROJECT";
	static String newProjectCardKey = "NEW PROJECT";
	static String mainInterfaceCardKey = "MAIN INTERFACE";
	static String saveProjectCardKey = "SAVE PROJECT";
	static String timeSignatureEventCardKey = "TIME SIGNATURE";
	static String tempoEventCardKey = "TEMPO EVENT";
	static String notesEventCardKey = "NEW NOTE";;
	
	static int numEventRows = 3;
	private ProjectFileData projectData = null;
	private CanvasesConfig canvasesConfig = CanvasesConfig.getXMLInstance();
	private Font font = new Font("Monospaced", Font.BOLD, 10);
	private FontMetrics fontMetrics = new Canvas().getFontMetrics(font);
	int infoPanelHeight = fontMetrics.getMaxAscent() + 10;
	int cellWidth = fontMetrics.stringWidth("88") + 3;
	int rowHeight = fontMetrics.getMaxAscent() + 2;
	final int scrollTimeMargin = 4;
	static final double MIDDLE_C = 220.0 * Math.pow(2d, 3.0 / 12.0);

	FileFilter fileFilter = new FileNameExtensionFilter(".tab files (.tab)", "tab");
	final File defaultProjectPath = new File("scores");

	final Map<Point, String> instrumentClipboard = new HashMap<>();
	final Map<Point, ControlEvent> eventClipboard = new HashMap<>();

	final AtomicReference<File> activeFile = new AtomicReference<>(null);
	final AtomicBoolean fileHasBeenModified = new AtomicBoolean(false);
	final AtomicBoolean isSelectionMode = new AtomicBoolean(false);
	final AtomicBoolean isPlaying = new AtomicBoolean(false);

	final AtomicInteger tempo = new AtomicInteger(120);
	final AtomicBoolean playbackDaemonIsStarted = new AtomicBoolean(false);

	final TreeMap<Integer, Integer> cachedMeasurePositions = new TreeMap<>();
	final HashSet<Integer> cachedBeatMarkerPositions = new HashSet<>();

	final ScheduledExecutorService playbackDaemon = Executors.newSingleThreadScheduledExecutor();
	final ScheduledExecutorService midiDaemon = Executors.newSingleThreadScheduledExecutor();

	final JFrame frame = new JFrame("TabbyCat");

	LeftClickablePanelButton playButton, stopButton;
	final PlayStatusPanel playStatusPanel = new PlayStatusPanel();
	final NavigationCanvas navigationBar = new NavigationCanvas();
	final EventCanvas eventCanvas = new EventCanvas();
	File defaultSoundfontFile = null;

	final Map<File, Soundbank> loadedSoundfontCache = new HashMap<>();

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
	
	KeyStroke k_CtrlL = KeyStroke.getKeyStroke("ctrl L");
	KeyStroke k_CtrlC = KeyStroke.getKeyStroke("ctrl C");
	KeyStroke k_CtrlV = KeyStroke.getKeyStroke("ctrl V");
	KeyStroke k_CtrlX = KeyStroke.getKeyStroke("ctrl X");
	KeyStroke k_CtrlR = KeyStroke.getKeyStroke("ctrl R");
	
	KeyStroke k_Space = KeyStroke.getKeyStroke("SPACE");

	

	void playbackDaemonFunction() {
		// DO NOT CALL THIS ON MASTER THREAD
		while (true) {
			if (!isPlaying.get()) {
				continue;
			} else {
				int t = projectData.getPlaybackT().get();

				try {

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
				long bpm = tempo.get();
				Duration sixteenth = Duration.ofMinutes(1).dividedBy(bpm).dividedBy(4);
				playbackDaemon.schedule(() -> playbackDaemonFunction(), sixteenth.toMillis(), TimeUnit.MILLISECONDS);

				return;
			}
		}
	}
	
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
		projectData.getInstrumentData().entrySet().stream()
		.filter(e->e.getKey().getTime() == t).map(e->e.getValue())
		.forEach(a -> {
			System.out.println(a);
		});
	}


	void pasteSelectedNotes() {
		// TODO PUT TIHS BACK
		/*
		 * if (selectedCanvas.get()== eventCanvas) { int timeOffset =
		 * eventClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt(); int
		 * rowOffset =
		 * eventClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
		 * eventClipboard.forEach((point,controlEvent) -> { int t =
		 * projectData.getCursorT().get()+point.y-timeOffset; int row =
		 * eventCanvas.getSelectedRow()+point.x-rowOffset;
		 * eventCanvas.setSelectedValue(row,t,controlEvent); });
		 * updateMeasureLinePositions(); } else if (selectedCanvas.get() instanceof
		 * InstrumentCanvas) { int timeOffset =
		 * instrumentClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt(); int
		 * rowOffset =
		 * instrumentClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
		 * instrumentClipboard.forEach((point,s) -> { int t =
		 * projectData.getCursorT().get()+point.y-timeOffset; int row =
		 * selectedCanvas.get().getSelectedRow()+point.x-rowOffset; ((InstrumentCanvas)
		 * selectedCanvas.get()).setSelectedValue(row,t,s); });
		 * 
		 * }
		 */
	}

	void cutSelectedNotes() {

		instrumentClipboard.clear();
		eventClipboard.clear();

		if (isSelectionMode.get()) {
			/*
			 * Stream.of(selectedCanvas.get().innerSelectionCells,selectedCanvas.get().
			 * outerSelectionCells) .flatMap(a->a.stream()).forEach(point -> {
			 * 
			 * Optional<?> opt = selectedCanvas.get().getValueAt(point.y,point.x); if
			 * (opt.isPresent()) { if (selectedCanvas.get() instanceof EventCanvas) {
			 * eventClipboard.put(point, (ControlEvent) opt.get()); } else if
			 * (selectedCanvas.get() instanceof InstrumentCanvas) {
			 * instrumentClipboard.put(point, (String) opt.get()); } }
			 * selectedCanvas.get().removeValueAt(point.y,point.x); });
			 * selectedCanvas.get().clearSelectionT0AndRow();
			 */
		}

		isSelectionMode.set(false);
	}

	void copySelectedNotes() {
		instrumentClipboard.clear();
		eventClipboard.clear();

		if (isSelectionMode.get()) {
			// TODO put this back
			/*
			 * Stream.of(selectedCanvas.get().innerSelectionCells,selectedCanvas.get().
			 * outerSelectionCells) .flatMap(a->a.stream()).forEach(point -> {
			 * 
			 * Optional<?> opt = selectedCanvas.get().getValueAt(point.y,point.x); if
			 * (opt.isPresent()) { if (selectedCanvas.get() instanceof EventCanvas) {
			 * 
			 * eventClipboard.put(point, (ControlEvent) opt.get()); } else if
			 * (selectedCanvas.get() instanceof InstrumentCanvas) {
			 * instrumentClipboard.put(point, (String) opt.get()); } } });
			 * selectedCanvas.get().clearSelectionT0AndRow();
			 */
		}
		isSelectionMode.set(false);
	}

	

	void configureCanvasInstrument() {
		// TODO put this back
		/*
		 * if (selectedCanvas.get() instanceof TabCanvas) {
		 * ((TabCanvas)selectedCanvas.get()).displayInstrumentDialog(); } else if
		 * (selectedCanvas.get() instanceof DrumTabCanvas) {
		 * ((DrumTabCanvas)selectedCanvas.get()).displayInstrumentDialog(); }
		 */
	}

	void saveActiveFile() {
		if (!defaultProjectPath.exists()) {
			defaultProjectPath.mkdir();
		}
		if (activeFile.get() != null) {
			if (fileHasBeenModified.get()) {
				try {
					saveXML(activeFile.get());
					fileHasBeenModified.set(false);
					updateWindowTitle();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		} else {
			JFileChooser chooser = new JFileChooser(defaultProjectPath);
			chooser.setFileFilter(fileFilter);

			String date = LocalDateTime.now(ZoneId.of("Z")).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
			chooser.setSelectedFile(new File(date + ".tab"));
			if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
				try {
					File f = chooser.getSelectedFile();
					if (!f.getAbsolutePath().endsWith(".tab")) {
						f = new File(f.getAbsoluteFile() + ".tab");
					}
					saveXML(f);
					activeFile.set(f);
					fileHasBeenModified.set(false);
					updateWindowTitle();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}

		}
	}

	void openFileDialog() {
		File defaultPath = new File("scores");
		if (!defaultPath.exists()) {
			defaultPath.mkdir();
		}
		JFileChooser fileChooser = new JFileChooser(defaultPath);
		fileChooser.setFileFilter(fileFilter);
		if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			try {

				loadXML(fileChooser.getSelectedFile());
				activeFile.set(fileChooser.getSelectedFile());
				fileHasBeenModified.set(false);
				updateWindowTitle();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
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

	void adjustRepeatT() {
		projectData.getRepeatT()
				.updateAndGet(i -> i == projectData.getCursorT().get() ? -1 : projectData.getCursorT().get());
	
	}

	void setPlayT() {
		projectData.getPlaybackT().set(projectData.getCursorT().get());
	
	}

	void togglePlayStatus() {
		if (isPlaying.get()) {
			stopPlayback();
		} else {
			startPlayback();
		}
	}

	void returnCursorToStopT0() {
		projectData.getCursorT().set(projectData.getPlaybackStartT().get());
		while (projectData.getCursorT().get() < projectData.getViewT().get() + scrollTimeMargin) {
			projectData.getViewT().getAndDecrement();
		}
	
	}

	void returnPlayToStopT0() {
		projectData.getPlaybackT().set(projectData.getPlaybackStartT().get());
		while (projectData.getPlaybackT().get() < projectData.getViewT().get() + scrollTimeMargin) {
			projectData.getViewT().getAndDecrement();
		}
	

	}

	void startPlayback() {
		if (!playbackDaemonIsStarted.get()) {
			playbackDaemonIsStarted.set(true);
			playbackDaemon.schedule(() -> playbackDaemonFunction(), 0, TimeUnit.SECONDS);
		}
		isPlaying.set(true);
		playStatusPanel.setPlayStatus(PlayStatus.PLAY);
		playStatusPanel.repaint();
	}

	void stopPlayback() {
		isPlaying.set(false);

		// TODO put this back
		/*
		 * instrumentCanvases.forEach(soundfontPlayer -> { soundfontPlayer.silence();
		 * });
		 * 
		 */
		playStatusPanel.setPlayStatus(PlayStatus.STOP);
		playStatusPanel.repaint();
	}

	void setStopT0() {
		projectData.getPlaybackStartT().set(projectData.getCursorT().get());
	}

	public int getCursorT() {
		return projectData.getCursorT().get();
	}

	public void setCursorT(int t) {
		projectData.getCursorT().set(t);
	}

	

	

	void setSelectedT0() {
		// TODO fix me
		/*
		 * allCanvases.forEach(canvas -> { if (canvas.isSelected()) {
		 * canvas.setSelectionT0AndRow();
		 * 
		 * } else { canvas.clearSelectionT0AndRow(); } canvas.repaint(); });
		 */
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
			Optional<TimeSignatureEvent> tsO = IntStream.range(0, eventCanvas.getRowCount())
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

	public void backspace() {
		// TODO fix
		/*
		 * Optional<?> removed = selectedCanvas.get().removeSelectedValue();
		 * 
		 * removed.filter(a->a instanceof TimeSignatureEvent).ifPresent(object ->{
		 * updateMeasureLinePositions(); repaintCanvases(); });
		 * 
		 * if (!fileHasBeenModified.get()) { fileHasBeenModified.set(true);
		 * updateWindowTitle(); } selectedCanvas.get().repaint();
		 */
	}

	public void handleABCInput(char c) {
		projectData.handleCharInput(c);
		// selectedCanvas.get().handleCharInput(c);

	}

	public void cursorToStart() {
		projectData.getCursorT().set(0);
		while (projectData.getCursorT().get() < projectData.getViewT().get()) {
			projectData.getViewT().getAndDecrement();
		}
	
	}

	void updateDataLength() {
		// int maxT=
		// allCanvases.stream().flatMapToInt(c->c.data.keySet().stream().mapToInt(i->i)).max().orElseGet(()->0)+16;
		int maxT = 10;

		navigationBar.dataLength = maxT;
		navigationBar.repaint();
		System.out.println(maxT);

	}

	

	


	public void shiftArrowUp() {
		if (isSelectionMode.get()) {
			return;
		}
		// TODO fix this
		/*
		 * if (selectedCanvas.get() == null) { selectedCanvas.set(allCanvases.get(0));
		 * allCanvases.get(0).setSelectedRow(allCanvases.get(0).getRowCount()-1);
		 * selectedCanvas.get().repaint(); } else { int index =
		 * allCanvases.indexOf(selectedCanvas.get())-1; if (index == -1) {
		 * index+=allCanvases.size(); } selectedCanvas.get().repaint();
		 * selectedCanvas.set(allCanvases.get(index));
		 * selectedCanvas.get().setSelectedRow(selectedCanvas.get().getRowCount()-1);
		 * selectedCanvas.get().repaint(); }
		 */
	}

	public void shiftArrowDown() {
		if (isSelectionMode.get()) {
			return;
		}
		// TODO fix this
		/*
		 * if (selectedCanvas.get() == null) {
		 * selectedCanvas.set(allCanvases.get(allCanvases.size()-1));
		 * eventCanvas.setSelectedRow(0); eventCanvas.repaint(); } else { int index =
		 * allCanvases.indexOf(selectedCanvas.get())+1; if (index == allCanvases.size())
		 * { index = 0; } selectedCanvas.get().repaint();
		 * selectedCanvas.set(allCanvases.get(index));
		 * selectedCanvas.get().setSelectedRow(0); selectedCanvas.get().repaint(); }
		 */

	}

	public void arrowUp() {
		// TODO fix this
		/*
		 * if (selectedCanvas.get() == null) { selectedCanvas.set(allCanvases.get(0));
		 * allCanvases.get(0).setSelectedRow(allCanvases.get(0).getRowCount()-1);
		 * selectedCanvas.get().repaint(); } else {
		 * 
		 * if (selectedCanvas.get().getSelectedRow() == 0) { shiftArrowUp(); } else {
		 * selectedCanvas.get().setSelectedRow(selectedCanvas.get().getSelectedRow()-1);
		 * selectedCanvas.get().repaint(); } }
		 */
	}

	public void arrowDown() {
		// TODO fix this
		/*
		 * if (selectedCanvas.get() == null) {
		 * selectedCanvas.set(allCanvases.get(allCanvases.size()-1));
		 * eventCanvas.setSelectedRow(0); eventCanvas.repaint(); } else { if
		 * (selectedCanvas.get().getSelectedRow() ==
		 * selectedCanvas.get().getRowCount()-1) { shiftArrowDown(); } else {
		 * selectedCanvas.get().setSelectedRow(selectedCanvas.get().getSelectedRow()+1);
		 * selectedCanvas.get().repaint(); } }
		 */
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

	abstract class GeneralTabCanvas<A> extends JPanel {

		// protected final TreeMap<Integer,Map<Integer,A>> data = new TreeMap<>();
		AtomicInteger selectedRow = new AtomicInteger(0);
		AtomicInteger selectionT0 = new AtomicInteger(-1);
		AtomicInteger selectionRow0 = new AtomicInteger(-1);

		public abstract int getRowCount();

		public abstract void handleEvents(int t);

		public abstract boolean handleCharInput(char c);

		public abstract String getName();

		GeneralTabCanvas() {
			this.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent me) {
					/*
					 * int t_ = (int)
					 * Math.floor(me.getPoint().getX()/cellWidth)+projectData.getViewT().get(); int
					 * row = (int) Math.floor((me.getPoint().y-infoPanelHeight)/rowHeight); if
					 * (selectedCanvas.get() != null) { selectedCanvas.get().repaint(); }
					 * selectedCanvas.set(GeneralTabCanvas.this);
					 * selectedCanvas.get().setSelectedRow(row); if (t_ >= 0) {
					 * 
					 * setCursorT(t_);
					 * 
					 * if (me.isShiftDown()) { setPlayT(); } else if(me.isControlDown()) {
					 * setStopT0(); } else if (me.isAltDown()) { adjustRepeatT(); }
					 * selectedCanvas.get().repaint(); }
					 */
				}
			});
		}

		public final void setSelectionT0AndRow() {
			selectionT0.set(projectData.getCursorT().get());
			selectionRow0.set(getSelectedRow());
		}

		public final void clearSelectionT0AndRow() {
			selectionT0.set(-1);
			selectionRow0.set(-1);
		}

		public final boolean isSelected() {
			return false;
			// TODO fix this!
			// return this == selectedCanvas.get();
		}

		private Color selectedLabelTextColor = Color.yellow;
		private Color unselectedLabelTextColor = Color.white;
		private Color gridColor = new Color(100, 100, 100);
		private Color selectedHeaderBackgroundColor = Color.black;
		private Color unselectedHeaderBackgroundColor = Color.black;
		private Color evenGridBackground = new Color(20, 20, 20);
		private Color oddGridBackground = new Color(30, 30, 30);
		private Color playTColor = new Color(50, 50, 50);
		private Color selectedMeasureColor = Color.LIGHT_GRAY;
		private Color unselectedMeasureColor = Color.LIGHT_GRAY;
		private Color selectedCellColor = Color.red;
		private Color repeatColor = Color.orange.darker();
		private Color selectionOuterColor = new Color(0, 150, 100);
		private Color selectionInnerColor = new Color(0, 75, 50);

		protected final Set<Point> outerSelectionCells = new HashSet<>();
		protected final Set<Point> innerSelectionCells = new HashSet<>();

		public final void drawGrid(Graphics2D g) {
			g.setFont(font);
			outerSelectionCells.clear();
			innerSelectionCells.clear();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(isSelected() ? selectedHeaderBackgroundColor : unselectedHeaderBackgroundColor);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setStroke(new BasicStroke(1));
			g.setPaint(isSelected() ? selectedLabelTextColor : unselectedLabelTextColor);
			g.drawString(getName(), 2, fontMetrics.getMaxAscent());
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth() / cellWidth;
			int t1 = t0 + tDelta;

			{
				int y = infoPanelHeight;

				for (int row = 0; row < getRowCount(); row++) {
					g.setPaint(row % 2 == 0 ? evenGridBackground : oddGridBackground);
					g.fillRect(0, y, getWidth(), rowHeight);
					y += rowHeight;
				}

			}
			int x = 0;
			Path2D.Double p2d = new Path2D.Double();
			IntStream.range(0, getRowCount() + 1).map(a -> infoPanelHeight + a * rowHeight).forEach(y -> {
				p2d.append(new Line2D.Double(0, y, getWidth(), y), false);
			});
			for (int t = t0; t < t1; t++) {
				p2d.append(new Line2D.Double(x, infoPanelHeight, x, infoPanelHeight + rowHeight * getRowCount()),
						false);
				x += cellWidth;
			}

			x = 0;
			for (int t = t0; t < t1; t++) {
				if (t == projectData.getPlaybackT().get()) {
					g.setPaint(playTColor);
					g.fillRect(x, infoPanelHeight, cellWidth, rowHeight * getRowCount());
				}
				g.setPaint(isSelected() ? selectedMeasureColor : unselectedMeasureColor);
				g.setFont(font.deriveFont(Font.PLAIN));

				if (cachedMeasurePositions.containsKey(t)) {
					int measure = cachedMeasurePositions.get(t);
					g.drawString("" + measure, x, infoPanelHeight);
				}
				if (cachedBeatMarkerPositions.contains(t)) {
					g.drawString(".", x, infoPanelHeight);
				}
				x += cellWidth;
			}

			if (isSelected() && selectionT0.get() != -1 && selectionRow0.get() != -1) {
				// means we're selecting things
				int sT0 = Math.min(projectData.getCursorT().get(), selectionT0.get());
				int sT1 = Math.max(projectData.getCursorT().get(), selectionT0.get());
				int row0 = Math.min(getSelectedRow(), selectionRow0.get());
				int row1 = Math.max(getSelectedRow(), selectionRow0.get());
				IntStream.rangeClosed(row0, row1).forEach(row -> {
					outerSelectionCells.add(new Point(row, sT0));
					outerSelectionCells.add(new Point(row, sT1));
				});
				IntStream.rangeClosed(sT0, sT1).forEach(t -> {
					outerSelectionCells.add(new Point(row0, t));
					outerSelectionCells.add(new Point(row1, t));
				});

				for (int row = 0; row < getRowCount(); row++) {
					for (int t = t0; t < t1; t++) {
						Point p = new Point(row, t);
						if ((row == row0 || row == row1) && (t >= sT0 && t <= sT1)) {
							outerSelectionCells.add(p);
						} else if ((t == sT0 || t == sT1) && (row >= row0 && row <= row1)) {
							outerSelectionCells.add(p);
						} else if (row > row0 && row < row1 && t > sT0 && t < sT1) {
							innerSelectionCells.add(p);
						}
					}
				}
			}

			g.setPaint(gridColor);
			g.draw(p2d);
			x = 0;

			for (int t = t0; t < t1; t++) {
				g.setPaint(gridColor);
				if (cachedMeasurePositions.containsKey(t)) {
					g.setStroke(new BasicStroke(2));
					// g.drawLine(x, y-rowHeight, x, y);
				} else {
					g.setStroke(new BasicStroke(1));
				}
				int y = rowHeight + infoPanelHeight;
				for (int row = 0; row < getRowCount(); row++) {
					if (outerSelectionCells.contains(new Point(row, t))) {
						g.setPaint(selectionOuterColor);
						g.fillRect(x + 1, y - rowHeight + 1, cellWidth - 2, rowHeight - 2);
					}
					if (innerSelectionCells.contains(new Point(row, t))) {
						g.setPaint(selectionInnerColor);
						g.fillRect(x + 1, y - rowHeight + 1, cellWidth - 2, rowHeight - 2);
					}
					if (isSelected() && row == getSelectedRow() && t == projectData.getCursorT().get()) {
						g.setPaint(selectedCellColor);
						g.setStroke(new BasicStroke(1));
						g.drawRect(x, y - rowHeight, cellWidth, rowHeight);

					}
					y += rowHeight;
				}
				if (projectData.getPlaybackStartT().get() == t) {
					g.setStroke(new BasicStroke(1));
					g.setPaint(repeatColor);
					g.drawLine(x, infoPanelHeight, x, infoPanelHeight + getRowCount() * rowHeight);
					g.drawLine(x + 3, infoPanelHeight, x + 3, infoPanelHeight + getRowCount() * rowHeight);
				}

				if (projectData.getRepeatT().get() == t && t >= 0) {
					g.setStroke(new BasicStroke(1));
					g.setPaint(repeatColor);
					g.drawLine(x, infoPanelHeight, x, infoPanelHeight + getRowCount() * rowHeight);
					g.drawLine(x - 3, infoPanelHeight, x - 3, infoPanelHeight + getRowCount() * rowHeight);
				}
				x += cellWidth;
			}
		}

		

		// @SuppressWarnings("unchecked")
		public abstract Optional<A> getValueAt(int t, int row);

		/*
		 * { if (this == eventCanvas) { return (Optional<A>)
		 * Optional.ofNullable(eventData.get(new Point(t,row))); } else { return
		 * (Optional<A>) Optional.ofNullable(instrumentData.get(new
		 * InstrumentDataKey(this.getName(),t,row))); } }
		 */
		public final Optional<A> getPrecedingValue() {
			return getValueAt(projectData.getCursorT().get() - 1, getSelectedRow());
		}

		public final Optional<A> getSelectedValue() {
			return getValueAt(projectData.getCursorT().get(), getSelectedRow());
		}

		public abstract void setSelectedValue(int t, int row, A inputVal);

		public final void setSelectedValue(int t, A inputVal) {
			setSelectedValue(t, getSelectedRow(), inputVal);
		}

		public final void setSelectedValue(A inputVal) {
			setSelectedValue(projectData.getCursorT().get(), inputVal);
			updateDataLength();
		}

		public final Optional<A> removeSelectedValue() {
			return removeValueAt(projectData.getCursorT().get(), selectedRow.get());

		}

		public abstract Optional<A> removeValueAt(int t, int row);
		/*
		 * Optional<A> toReturn = Optional.ofNullable(data.getOrDefault(t, new
		 * HashMap<>()).get(row)); data.getOrDefault(t,new HashMap<>()).remove(row);
		 * return toReturn; }
		 */

		public final int getSelectedRow() {
			return selectedRow.get();
		}

		public final void setSelectedRow(int row) {
			selectedRow.set(row);
		}

	}

	class NavigationCanvas extends JPanel implements MouseMotionListener, MouseListener {

		private int dataLength = 16 * 256;

		public NavigationCanvas() {
			this.addMouseMotionListener(this);
			this.addMouseListener(this);
		}

		@Override
		public Dimension getSize() {
			return new Dimension(super.getWidth(), 20);
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;

			g.setPaint(Color.BLACK);
			g.fill(getBounds());
			double t0 = projectData.getViewT().get();

			double tDelta = getWidth() / cellWidth;
			double t1 = t0 + tDelta;
			double x0 = t0 / dataLength * getWidth();
			double x1 = t1 / dataLength * getWidth();
			g.setPaint(Color.WHITE);
			g.draw(new Rectangle2D.Double(x0, getBounds().y, x1 - x0 - 1, getHeight() - 1));
		}

		Integer pressedX = 0;

		@Override
		public void mousePressed(MouseEvent me) {
			pressedX = me.getPoint().x;
		}

		@Override
		public void mouseDragged(MouseEvent me) {
			if (pressedX == null) {
				System.err.println("wtf");
				return;
			}

			JPanel navigationBar = (JPanel) me.getSource();
			double deltaX = pressedX - me.getPoint().x;
			pressedX = me.getPoint().x;

			int deltaT = (int) (deltaX / navigationBar.getWidth() * dataLength);
			double t0 = projectData.getViewT().get();
			double displayedt1 = t0 + navigationBar.getWidth() / cellWidth;
			projectData.getViewT()
					.updateAndGet(a -> Math.min(dataLength - (int) (displayedt1 - t0), Math.max(0, a - deltaT)));
		
		}

		@Override
		public void mouseReleased(MouseEvent me) {
			pressedX = null;
		}

		@Override
		public void mouseClicked(MouseEvent e) {
		}

		@Override
		public void mouseEntered(MouseEvent e) {
		}

		@Override
		public void mouseExited(MouseEvent e) {
		}

		@Override
		public void mouseMoved(MouseEvent e) {
		}
	}

	class EventCanvas extends GeneralTabCanvas<ControlEvent> {

		public EventCanvas() {
		}

		@Override
		public int getRowCount() {
			return 3;
		}

		@Override
		public String getName() {
			return "Events";
		}

		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);			
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;			
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 					
				g.setFont(font);
				
				for (int rowNumber = 0; rowNumber < getRowCount(); rowNumber++) {					
					g.setPaint(Color.black);
					
					ControlEvent event = projectData.getEventData().get(new Point(t_,rowNumber));
					if (event != null) {
						switch (event.getType()) {
						
						case TIME_SIGNATURE:
							g.setFont(font.deriveFont(Font.ITALIC));
							g.setPaint(Color.YELLOW);
							g.drawString(event.toString(),x+1,y-3);
							g.setFont(font);
							break;
						case PROGRAM_CHANGE:
							g.setFont(font.deriveFont(Font.ITALIC));
							g.setPaint(new Color(100,200,255));
							g.drawString(event.toString(),x+1,y-3);
							g.setFont(font);
							break;
						case TEMPO:
							g.setFont(font.deriveFont(Font.ITALIC));
							g.setPaint(new Color(255,200,100));
							g.drawString(event.toString(),x+1,y-3);
							g.setFont(font);
							break;
						case STICKY_NOTE:
							g.setFont(font.deriveFont(Font.ITALIC));
							g.setPaint(Color.WHITE);
							g.drawString(((StickyNote) event).getText(),x+1,y-3);
						default:
							break;					
						}					
					}					
					y+=rowHeight;
				}				
				x+=cellWidth;
			}
			
			if (!eventClipboard.isEmpty()) {
				g.setPaint(Color.pink);
				int timeOffset = eventClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt();
				int rowOffset = eventClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
				eventClipboard.forEach((point,controlEvent) -> {
					int t = projectData.getCursorT().get()+point.y-timeOffset;
					int row = getSelectedRow()+point.x-rowOffset;
					int x_ = (t-t0)*cellWidth;
					int y = rowHeight+infoPanelHeight+(rowHeight*row);
					if (row >= 0) {
						g.drawString(controlEvent.toString(),x_+1,y-3);
					}
				});
				
			}
		}

		@Override
		public void handleEvents(int t) {
			for (int row = 0; row < getRowCount(); row++) {
				Optional<ControlEvent> controlEvent= this.getValueAt(t, row);
				controlEvent.filter(a->a.getType()==ControlEventType.TEMPO)
				.ifPresent(event -> {
					tempo.set(((TempoEvent) event).getTempo());
				});
				//TODO put this back
				/*
				controlEvent.filter(a->a.getType()==ControlEventType.PROGRAM_CHANGE)
				.ifPresent(event -> {
					allCanvases.stream().filter(a->a.getName().equals(((ProgramChange) event).getInstrument()))
					.findFirst()
					.ifPresent(canvas -> {
						try {
							((TabCanvas) canvas).programChange((ProgramChange) event);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					});
				});
				*/
			}			
		}

		@Override
		public boolean handleCharInput(char c) {
			
			switch (c) {
			case 'T':
				addTimeSignature();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				
				return true;
			case 'P':
				addProgramChange();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				
				return true;
			case 'S':
				addTempo();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				
				return true;
			case 'N':
				addStickyNote();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
					
				}
				return true;
			default:
				return false;				
			}			
			
		}

		public Optional<TimeSignatureEvent> getTimeSignatureEventForTime(int t_) {
			return Optional.ofNullable(projectData.getEventData().get(new Point(t_,getSelectedRow())))
					.filter(a->a.getType() == ControlEventType.TIME_SIGNATURE)
					.map(a->(TimeSignatureEvent) a);
			
		}	
		
		@Override
		public Optional<ControlEvent> getValueAt(int t, int row) {
			return Optional.ofNullable(projectData.getEventData().get(new Point(t,row)));
		}

		@Override
		public void setSelectedValue(int t, int row, ControlEvent inputVal) {
			projectData.getEventData().put(new Point(t,row), inputVal);
			
		}

		@Override
		public Optional<ControlEvent> removeValueAt(int t, int row) {
			Optional<ControlEvent> toReturn = Optional.ofNullable(projectData.getEventData().get(new Point(t,row)));
			projectData.getEventData().remove(new Point(t,row));
			return toReturn;
			
		}
	}
	
	class TabCanvas extends InstrumentCanvas  {		
		
		private final String name;		
		

		private final Map<Integer,String> openNotes = new ConcurrentHashMap<>();
		private final Map<MidiChannel,Integer> openMidiNums = new ConcurrentHashMap<>();
		
		Synthesizer synth = null;
		//Map<Integer,Synthesizer> synths = new HashMap<>();
		private File soundfontFile = defaultSoundfontFile;
		private final AtomicReference<Instrument> loadedInstrument = new AtomicReference<>(null);
		private final StringCanvasConfig canvasConfig;
				
		public TabCanvas(StringCanvasConfig config) {
			this.canvasConfig = config;
			
			this.name = config.getName();			
			initializeMidi(config.getSoundfontFile().orElse(defaultSoundfontFile),
					config.getBank(),config.getProgram());
		}

		public Synthesizer getSynth() {
			return synth;

		}
				
		public void displayInstrumentDialog() {
			JDialog dialog = new JDialog(frame,String.format("Configuring instruments for [%s]",
					getName()));
			dialog.setModalityType(ModalityType.APPLICATION_MODAL);
			JButton loadSoundfontButton = new JButton("Load Soundbank:");
			JTextField soundbankTextField = new JTextField(20);
			
			JList<Instrument> instrumentList = new JList<>();
			JScrollPane instrumentScrollPane = new JScrollPane(instrumentList,JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			
			soundbankTextField.setEditable(false);
			
			ListCellRenderer<? super Instrument> originalRenderer = instrumentList.getCellRenderer();
			
			instrumentList.setCellRenderer((list,value,index,isSelected,hasFocus) ->{
				JLabel label = (JLabel) originalRenderer.getListCellRendererComponent(list, value, index, isSelected, hasFocus);				
				 label.setText(String.format("%s (bank %d, program %d)",
						value.getName(),
						value.getPatch().getBank(),
						value.getPatch().getProgram()));
				if (value == loadedInstrument.get()) {
					label.setFont(label.getFont().deriveFont(Font.BOLD | Font.ITALIC));
				}
				return label;

			});
			
			loadSoundfontButton.addActionListener(ae -> {
				JFileChooser chooser = new JFileChooser("sf2");
				chooser.setFileFilter(new FileNameExtensionFilter("Soundfont files (.sf2)","sf2"));
				if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {					
					soundfontFile = chooser.getSelectedFile();
					soundbankTextField.setText(soundfontFile.getName());
					try {
						DefaultListModel<Instrument> model = new DefaultListModel<>();
						Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()).forEach(model::addElement);
							
						//model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
						instrumentList.setModel(model);						
					} catch (Exception ex) {
						ex.printStackTrace();
					}				
				}
			});
			
			if (soundfontFile != null) {
				soundbankTextField.setText(soundfontFile.getName());
				try {
					DefaultListModel<Instrument> model = new DefaultListModel<>();
					Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()).forEach(model::addElement);
					//model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
					instrumentList.setModel(model);
				} catch (Exception ex) {
					ex.printStackTrace();
				}				
			} else {
				soundbankTextField.setText("<default>");
				try {
					DefaultListModel<Instrument> model = new DefaultListModel<>();
					Arrays.asList(synth.getLoadedInstruments()).forEach(model::addElement);
					//model.addAll(Arrays.asList(synth.getLoadedInstruments()));					
					instrumentList.setModel(model);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
			
			loadSoundfontButton.addActionListener(ae -> {
				JFileChooser chooser = new JFileChooser("sf2");
				if (soundfontFile != null) {
					chooser.setSelectedFile(soundfontFile);
					if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
						soundfontFile = chooser.getSelectedFile();
						soundbankTextField.setText(soundfontFile.getName());
						try {
							DefaultListModel<Instrument> model = new DefaultListModel<>();
							Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()).forEach(model::addElement);
//							/model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
							instrumentList.setModel(model);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
				}
			});
			
			JButton loadInstrumentButton = new JButton("Load instrument");
			loadInstrumentButton.addActionListener(ae ->{				
				Instrument instrument = instrumentList.getSelectedValue();
				loadedInstrument.set(instrument);
				initializeMidi(soundfontFile,instrument.getPatch().getBank(),instrument.getPatch().getProgram());
				repaint();
			});
			
			JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
			topPanel.add(loadSoundfontButton);
			topPanel.add(soundbankTextField);
			 
			dialog.getContentPane().add(topPanel,BorderLayout.NORTH);
			dialog.getContentPane().add(instrumentScrollPane,BorderLayout.CENTER);
			dialog.getContentPane().add(loadInstrumentButton,BorderLayout.SOUTH);
			
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			dialog.setLocationRelativeTo(null);
			dialog.pack();
			dialog.setVisible(true);	
		}


		public void noteOff(int row) {
			openNotes.remove(row);
			processTabCanvasEvent(row,null);
		}
		
		public void noteOn(int row,String inputVal) {
			openNotes.put(row, inputVal);
			processTabCanvasEvent(row,inputVal);
		}
		
		void processTabCanvasEvent(int row,String inputVal) {
			MidiChannel channel = synth.getChannels()[row<9?row:row+1]; 			
			if (inputVal == null) {
				if (openMidiNums.containsKey(channel)) {
					channel.noteOff(openMidiNums.get(channel));
					openMidiNums.remove(channel);
				}			
			}
			
		}
		
		//public double[] getBaseFrequencies() {
			//return baseFrequencies;
		//}
				
		@Override
		public Dimension getSize() {				 			
			return new Dimension(super.getWidth(),infoPanelHeight + rowHeight * getRowCount());
		}		
		 
		@Override
		public void paint(Graphics g_) {
			
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);
			
			g.setFont(font.deriveFont(Font.ITALIC));
			g.setPaint(Color.WHITE);
			g.drawString(String.format("(loaded instrument: '%s')",
					loadedInstrument.get() == null ? "<null>" : loadedInstrument.get().getName()),					
					12+fontMetrics.stringWidth(getName()),fontMetrics.getMaxAscent());
			
			g.setPaint(Color.BLACK);
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 
				g.setFont(font);
				for (int row = 0; row< getRowCount(); row++) {
					g.setPaint(Color.WHITE);
					Point p = new Point(row,t_);
					if (outerSelectionCells.contains(p) || innerSelectionCells.contains(p)) {
						g.setPaint(Color.RED);
					}
					String s = projectData.getInstrumentData().getOrDefault(getDataKey(t_,row), "");					
					g.drawString(s,(int) (x+(cellWidth-fontMetrics.stringWidth(s))*0.5),y-3);
					y+=rowHeight;
				}
				x+=cellWidth;
			}
			if (!instrumentClipboard.isEmpty() && isSelected()) {
				g.setPaint(Color.pink);
				int timeOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt();
				int rowOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
				instrumentClipboard.forEach((point,guitarEvent) -> {
					int t = projectData.getCursorT().get()+point.y-timeOffset;
					int row = getSelectedRow()+point.x-rowOffset;
					int x_ = (t-t0)*cellWidth;
					int y = rowHeight+infoPanelHeight+(rowHeight*row);
					if (row >= 0) {
						g.drawString(guitarEvent.toString(),x_+1,y-3);
					}
				});
				
			}
		}

		@Override
		public int getRowCount() {			
			return canvasConfig.getEdoSteps().length;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public void handleEvents(int t) {
			
			BiConsumer<Integer,Double> f = (row, freq) -> {
				MidiChannel channel = synth.getChannels()[row<9?row:row+1];
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
					pb.setMessage(ShortMessage.PITCH_BEND, row, lsb, msb);
					synth.getReceiver().send(pb, -1);
					channel.noteOn(midiNote, 100);
					openMidiNums.put(channel,midiNote);
			 	} catch (Exception ex) {
			 		ex.printStackTrace();
			 	}
			};
			IntStream.range(0, getRowCount()).forEach(row -> {
				MidiChannel channel = synth.getChannels()[row<9?row:row+1];

				
				Optional<String> inputValO = this.getValueAt(t, row);
				if (inputValO.isPresent()) {
					String inputVal = inputValO.get();
					double baseFreq = canvasConfig.getBaseFrequency()*Math.pow(2.0, canvasConfig.getEdoSteps()[row]/canvasConfig.getEd2());

					if (inputVal.charAt(0) != '-') {
						//
						if (openMidiNums.containsKey(channel)) {
							channel.noteOff(openMidiNums.get(channel));
							openMidiNums.remove(channel);
						}
						
						if (inputVal.charAt(0) == 'H') {
							double freq = baseFreq;
							freq*=Integer.parseInt(inputVal.substring(1));

							f.accept(row, freq);
							
						} else if (canvasConfig.getAdditionalPitchMap().containsKey(inputVal)){
							
							double edoSteps = canvasConfig.getAdditionalPitchMap().get(inputVal);
							double freq = baseFreq*Math.pow(2, canvasConfig.getFretStepSkip()*edoSteps/canvasConfig.getEd2());
							f.accept(row, freq);
						} else {
							
							double edoSteps = Double.parseDouble(inputVal);
							
							double freq = baseFreq*Math.pow(2.0, canvasConfig.getFretStepSkip()*edoSteps/canvasConfig.getEd2());
							System.out.println(getName()+" "+canvasConfig.getFretStepSkip()*edoSteps/canvasConfig.getEd2());
							System.out.println(canvasConfig.getFretStepSkip()+" "+edoSteps+" "+canvasConfig.getEd2());
							
							f.accept(row, freq);
						}					
					}
				} else  {
					noteOff(row);
				}
			});
			
			
		}

		
		
		@Override
		public void initializeMidi(File file, int bank, int program) {
			try {
				this.soundfontFile = file;
				if (synth == null) {
					synth = MidiSystem.getSynthesizer();
				} 
				if (!synth.isOpen()) {
					synth.open();
				}
				if (this.soundfontFile != null) {
					Soundbank soundbank = MidiSystem.getSoundbank(soundfontFile);
					synth.unloadAllInstruments(synth.getDefaultSoundbank());
					Stream.of(soundbank.getInstruments()).filter(a->
						a.getPatch().getBank() == bank && a.getPatch().getProgram() == program)
					.findFirst().ifPresent(instrument -> {
						loadedInstrument.set(instrument);
						synth.loadInstrument(instrument);
					});				
				}
				
				for (int row = 0; row < getRowCount(); row++) {				
					synth.getChannels()[row<9?row:row+1].programChange(bank,program);
				}
				
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		
			
		}

		@Override
		public void silence() {
			for (int row = 0; row < getRowCount(); row++) {
				MidiChannel channel = synth.getChannels()[row<9?row:row+1];
				channel.allSoundOff();
			}
			
		}

		Pattern harmonicPattern = Pattern.compile("^H(\\d*)$");
		Pattern integerPattern = Pattern.compile("^(\\d+)$");
		
		@Override
		public boolean handleCharInput(char c) {
			if (c == '-') {
				{
					int t = projectData.getCursorT().get()+1;
				
					while (!getValueAt(t,getSelectedRow()).filter(a->a.contentEquals("-")).isEmpty()) {
						//instrument
						projectData.getInstrumentData().remove(getDataKey(t,getSelectedRow()));
						t++;
						//projectData.getInstrumentData().getOrDefault(new t, Collections.emptyMap()).remove(getSelectedRow());
						//					t++;
					}
				}
				IntStream.iterate(projectData.getCursorT().get(),i->i-1).takeWhile(i->i>=0)
				.filter(t->
					projectData.getInstrumentData().get(getDataKey(t,getSelectedRow())) != null)
				.filter(t->projectData.getInstrumentData().get(getDataKey(t,getSelectedRow())).charAt(0) != '-')
				.max().ifPresent(previousEventT0 -> {
					for (int t = previousEventT0+1; t < projectData.getCursorT().get(); t+=1) {
						projectData.getInstrumentData().put(getDataKey(t,getSelectedRow()),"-");
					}
				});
				
								
				setSelectedValue(String.valueOf(c));
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				repaint();
				return true;
			}
			
			String token0 = getValueAt(projectData.getCursorT().get(),getSelectedRow()).orElse("");
			String token1 = token0+String.valueOf(c);
			
			Predicate<String> isValid = token -> {
				Matcher harmonicMatcher = harmonicPattern.matcher(token);
				if (harmonicMatcher.find() && (harmonicMatcher.group(1).trim().isEmpty() || 
						Integer.parseInt(harmonicMatcher.group(1)) <= canvasConfig.getMaxHarmonic())) {					
					return true;
				}
				Matcher integerMatcher = integerPattern.matcher(token);
				if (integerMatcher.find() && Integer.parseInt(token) <= canvasConfig.getMaxFrets()) {
					return true;
				}
				if (canvasConfig.getAdditionalPitchMap().keySet().contains(token)) {
					return true;
				}
				return false;				
			};
			
			if (isValid.test(token1)) {
				setSelectedValue(token1);
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				repaint();				
				return true;
			} 
			if (isValid.test(String.valueOf(c))) {
				setSelectedValue(String.valueOf(c));
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				repaint();
				
				return true;
			}
			
			
			return false;
		}

		@Override
		public void programChange(ProgramChange event) {
			
			Arrays.asList(synth.getAvailableInstruments()).stream().filter(a->
			a.getPatch().getBank() == event.getBank()&& a.getPatch().getProgram() == event.getProgram())
			.findFirst().ifPresent(instrument -> {					
				loadedInstrument.set(instrument);					
				synth.loadInstrument(instrument);
				synth.getChannels()[9].programChange(event.getBank(),event.getProgram());
			
				for (int row = 0; row < getRowCount(); row++) {
					MidiChannel channel = synth.getChannels()[row<9?row:row+1];
					channel.programChange(event.getBank(), event.getProgram());
				}
			});
			
		}

		
	}
	
	abstract class InstrumentCanvas extends GeneralTabCanvas<String> {
		public abstract void initializeMidi(File file, int bank, int program);
		public void programChange(ProgramChange event) {
			
		}
		public void silence() {
			
		}
		public abstract Synthesizer getSynth();

		@Override
		public final Optional<String> getValueAt(int t, int row) {
			return Optional.ofNullable(projectData.getInstrumentData().get(getDataKey(t,row)));
		}
		
		public final InstrumentDataKey getDataKey(int t, int row) {
			return new InstrumentDataKey(this.getName(),t,row);
		}
		@Override
		public final void setSelectedValue(int t, int row, String value) {
			projectData.getInstrumentData().put(getDataKey(t,row),value);
		}
		
		@Override
		public final Optional<String> removeValueAt(int t, int row) {
			Optional<String> toReturn = Optional.ofNullable(projectData.getInstrumentData().get(getDataKey(t,row)));
			projectData.getInstrumentData().remove(getDataKey(t,row));
			return toReturn;
		}
		
	}
	
	class DrumTabCanvas extends InstrumentCanvas  {

		Synthesizer synth = null;
				
		private final AtomicReference<Instrument> loadedInstrument = new AtomicReference<>(null);
		private final DrumCanvasConfig canvasConfig;
		
		public Synthesizer getSynth() {
			return synth;
		}
		
		public DrumTabCanvas(DrumCanvasConfig canvasConfig) {
			this.canvasConfig = canvasConfig;
			initializeMidi(canvasConfig.getSoundfontFile().orElse(defaultSoundfontFile),
					canvasConfig.getBank(),canvasConfig.getProgram());
		}
		
		@Override
		public void initializeMidi(File file, int bank, int program) {
			try {
				synth = MidiSystem.getSynthesizer();
				synth.open();
				
				Soundbank soundbank = file == null ? synth.getDefaultSoundbank() : MidiSystem.getSoundbank(file);
				
				List<Instrument> percussionInstruments = new ArrayList<>();
				
				for (Instrument instrument : soundbank.getInstruments()) {
					boolean bank128 = instrument.getPatch().getBank() == 128;
					boolean matchesRegex = instrument.getName().toLowerCase().contains("drum") ||
							instrument.getName().toLowerCase().contains("perc");
					if (bank128 || matchesRegex) {
						percussionInstruments.add(instrument);
					}
				}	
				
				percussionInstruments.stream().filter(a->
				a.getPatch().getBank() == bank && a.getPatch().getProgram() == program)
				.findFirst().ifPresent(instrument -> {					
					loadedInstrument.set(instrument);					
					synth.loadInstrument(instrument);
					synth.getChannels()[9].programChange(bank, program);
				});				
					
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private final Set<Integer> openNotes = new HashSet<>();
		
		@Override
		public void handleEvents(int t) {
			MidiChannel channel = synth.getChannels()[9];
			openNotes.forEach(channel::noteOff);
			openNotes.clear();
			projectData.getInstrumentData().keySet().stream().filter(a->a.getTime() == t).map(projectData.getInstrumentData()::get).forEach(s -> {
				
			});
		}

		@Override
		public void paint(Graphics g_) {
			
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);
			
			g.setFont(font.deriveFont(Font.ITALIC));
			g.setPaint(Color.WHITE);
			g.drawString(String.format("(loaded instrument: '%s')",
					loadedInstrument.get() == null ? "<null>" : loadedInstrument.get().getName()),					
					12+fontMetrics.stringWidth(getName()),fontMetrics.getMaxAscent());
						
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;			
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 												
				g.setFont(font);
				for (int row= 0; row < getRowCount(); row++) {																								
					g.setPaint(Color.WHITE);
					int y_ = y;
					int x_ = x;

					this.getValueAt(t_, row).ifPresent(s->{
						g.drawString(s,(int) (x_+(cellWidth-fontMetrics.stringWidth(s))*0.5),y_-3);	
					});

					y+=rowHeight;
				}				
				x+=cellWidth;
			}
			if (!instrumentClipboard.isEmpty()) {
				g.setPaint(Color.pink);
				int timeOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt();
				int rowOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
				instrumentClipboard.forEach((point,drumEvent) -> {
					int t = projectData.getCursorT().get()+point.y-timeOffset;
					int row = getSelectedRow()+point.x-rowOffset;
					int x_ = (t-t0)*cellWidth;
					int y = rowHeight+infoPanelHeight+(rowHeight*row);
					if (row >= 0) {
						g.drawString(drumEvent,x_+1,y-3);
					}
				});
				
			}
		}

		@Override
		public int getRowCount() {
			return canvasConfig.getRowTypes().size();
		}

		@Override
		public String getName() {
			return canvasConfig.getName();
		}
		@Override
		public void silence() {
			MidiChannel channel = synth.getChannels()[9];
			channel.allSoundOff();
			
			
		}

		@Override
		public boolean handleCharInput(char c) {
			
			Optional<String> tokenO = getValueAt(projectData.getCursorT().get(),getSelectedRow());			
			Predicate<PercToken> permitted = a->a.getPosition() == canvasConfig.getRowTypes().get(getSelectedRow());
			if (tokenO.isPresent()) {
				Optional<PercToken> newTokenO = 
						canvasConfig.getTokens().stream()
						.filter(a->a.getToken().equals(tokenO.get()+String.valueOf(c).toUpperCase()))
						.filter(permitted).findFirst();
				if (newTokenO.isPresent()) {					
					setSelectedValue(projectData.getCursorT().get(),newTokenO.get().getToken());
					repaint();
					return true;
				}				
			}	
			
			Optional<PercToken> newTokenO = 
					canvasConfig.getTokens().stream().filter(a->a.getToken().equals(String.valueOf(c).toUpperCase()))
					.filter(permitted).findFirst();
			
			if (newTokenO.isPresent()) {					
				setSelectedValue(projectData.getCursorT().get(),newTokenO.get().getToken());
				repaint();
				return true;
			}			
			return false;
		}

		@Override
		public void programChange(ProgramChange event) {

			Arrays.asList(synth.getAvailableInstruments()).stream().filter(a->
			a.getPatch().getBank() == event.getBank()&& a.getPatch().getProgram() == event.getProgram())
			.findFirst().ifPresent(instrument -> {					
				loadedInstrument.set(instrument);					
				synth.loadInstrument(instrument);
				synth.getChannels()[9].programChange(event.getBank(),event.getProgram());
			});
		}
	}
	
	/*
	void initializeCanvases() {
		try {
			CanvasesConfig config = CanvasesConfig.getXMLInstance();
			for (CanvasConfig canvasConfig : config.getCanvases()) {
				switch (canvasConfig.getType()) {
				case DRUM: {
					DrumCanvasConfig drumConfig = (DrumCanvasConfig) canvasConfig;					
					DrumTabCanvas canvas = new DrumTabCanvas(drumConfig);
					allCanvases.add(canvas);
					instrumentCanvases.add(canvas);
					break;
				}
				case STRING:{
					StringCanvasConfig stringConfig = (StringCanvasConfig) canvasConfig;
					TabCanvas canvas = new TabCanvas(stringConfig);
					allCanvases.add(canvas);
					instrumentCanvases.add(canvas);
					break;
				}
				default:
					break;
				
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		
	}
	
	*/
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
	
	
	void addProgramChange() {
		JDialog dialog = new JDialog(frame,String.format("Adding program change(t = %d)",projectData.getCursorT().get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		JLabel canvasLabel = new JLabel("Canvas");
		DefaultComboBoxModel<String> canvasModel = new DefaultComboBoxModel<>();
		Map<String,InstrumentCanvas> nameToPlayerMap = new HashMap<>();
		/*
		allCanvases.stream().filter(a->a!=eventCanvas).forEach(a-> {
			nameToPlayerMap.put(a.getName(),(InstrumentCanvas) a);
			canvasModel.addElement(a.getName());	
		});
		*/
		JComboBox<String> canvasComboBox = new JComboBox<>(canvasModel);
		
		JList<Instrument> instrumentList = new JList<>();
		
		Runnable instrumentComboBoxF = () -> {
			Instrument[] instruments = 
					nameToPlayerMap.get(canvasComboBox.getSelectedItem().toString()).getSynth().getAvailableInstruments();
			//Instrument[] instruments = ((SoundfontPlayer) nameToPlayerMap.get(canvasComboBox.getSelectedItem().toString()))
					//.getSynth().getAvailableInstruments();
			DefaultListModel<Instrument> model = new DefaultListModel<>();
			//model.addAll(Arrays.asList(instruments));
			Arrays.asList(instruments).forEach(model::addElement);
			instrumentList.setModel(model);
		};
		instrumentComboBoxF.run();
		canvasComboBox.addActionListener(ae -> instrumentComboBoxF.run());
		
		JScrollPane instrumentScrollPane = new JScrollPane(instrumentList,JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		
		Runnable r = () -> {
			if (instrumentList.getSelectedValue() == null) {
				return;
			}
			ProgramChange programChange = new ProgramChange(
					nameToPlayerMap.get(canvasComboBox.getSelectedItem()).getName(),
					instrumentList.getSelectedValue().getPatch().getBank(),
					instrumentList.getSelectedValue().getPatch().getProgram());
			eventCanvas.setSelectedValue(programChange);
			eventCanvas.repaint();
			dialog.dispose();
		};
		Arrays.asList(canvasComboBox,instrumentList).forEach(component -> {
			component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
			.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
			component.getActionMap().put("enterPressed",rToA(r));
		});
		
		Box topPanel = Box.createHorizontalBox();
		topPanel.add(canvasLabel);
		topPanel.add(canvasComboBox);
		topPanel.add(Box.createHorizontalGlue());
		dialog.add(topPanel,BorderLayout.NORTH);
		dialog.add(instrumentScrollPane,BorderLayout.CENTER);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocationRelativeTo(null);
		
		dialog.pack();
		dialog.setVisible(true);

		
	}
	void addTempo() {
		JDialog dialog = new JDialog(frame,String.format("Adding tempo (t = %d)",projectData.getCursorT().get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		
		JLabel tempoLabel = new JLabel("Tempo");
		JSpinner tempoSpinner = new JSpinner(new SpinnerNumberModel(120,1,1000,1));
		
		Runnable r = () -> {
			TempoEvent tempo = new TempoEvent((int) tempoSpinner.getValue());
			eventCanvas.setSelectedValue(tempo);
			eventCanvas.repaint();
			dialog.dispose();
		};
		tempoSpinner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		tempoSpinner.getActionMap().put("enterPressed",rToA(r));
		
		dialog.getContentPane().add(tempoLabel,BorderLayout.WEST);
		dialog.getContentPane().add(tempoSpinner,BorderLayout.CENTER);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocationRelativeTo(null);
		
		dialog.pack();
		dialog.setVisible(true);
	}
	
	void addTimeSignature() {
		//TODO fix this
		/*
		if (selectedCanvas.get() != eventCanvas) {
			return;
		}
		JDialog dialog = new JDialog(frame,String.format("Adding time signature (t = %d)",projectData.getCursorT().get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		
		JPanel outerBox = new JPanel(new GridLayout(2,0));
		JLabel numeratorLabel = new JLabel("Numer");
		JLabel denominatorLabel = new JLabel("Denom");
		JSpinner numeratorSpinner = new JSpinner(new SpinnerNumberModel(4,1,Integer.MAX_VALUE,1));
		JComboBox<TimeSignatureDenominator> denominatorComboBox = new JComboBox<>(TimeSignatureDenominator.values());
		denominatorComboBox.setEditable(false);
		Runnable r = () -> {
			TimeSignatureEvent event = new TimeSignatureEvent((int) numeratorSpinner.getValue(),(TimeSignatureDenominator) denominatorComboBox.getSelectedItem());
			eventCanvas.setSelectedValue(event);
			updateMeasureLinePositions();
			repaintCanvases();
			dialog.dispose();
		};
		((JSpinner.DefaultEditor) numeratorSpinner.getEditor()).getTextField().addActionListener(ae -> r.run());
		denominatorComboBox.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		denominatorComboBox.getActionMap().put("enterPressed",rToA(r));
		numeratorSpinner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		numeratorSpinner.getActionMap().put("enterPressed",rToA(r));
		
		denominatorComboBox.setSelectedItem(TimeSignatureDenominator._4);
		outerBox.add(numeratorLabel);
		outerBox.add(numeratorSpinner);		
		outerBox.add(denominatorLabel);
		outerBox.add(denominatorComboBox);				
		
		dialog.getContentPane().add(outerBox,BorderLayout.CENTER);		
		
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);
	*/
	}
	
	void addStickyNote() {
		JDialog dialog = new JDialog(frame,String.format("Adding sticky note (t = %d)",projectData.getCursorT().get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);		
		
		JPanel topPanel = new JPanel();		
		JLabel textLabel = new JLabel("Text");

		
		JTextField textField = new JTextField("New note");
		
		eventCanvas.getSelectedValue().filter(a->a instanceof StickyNote).map(a->(StickyNote)a).ifPresent(stickyNote -> {
			textField.setText(stickyNote.getText());
		});
		AtomicReference<Color> color = new AtomicReference<>(Color.BLACK);
		
		JButton colorButton = new JButton("Color") {
			@Override
			public void paint(Graphics g_) {
				Graphics2D g = (Graphics2D) g_;
				Color c = color.get() == null ? Color.white :color.get();
				g.setPaint(c);
				g.fillRect(0,0,getWidth(),getHeight());
				float[] hsb = new float[3];
				Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), hsb);
				g.setPaint(hsb[2]>0.5?Color.BLACK:Color.WHITE);
				g.drawString("Color",2,getHeight()-1);				
			}
		};

		Runnable chooseColor = () -> {
			Color c = JColorChooser.showDialog(frame, "Choose color",color.get());
			if (c != null) {
				color.set(c);
			}
			colorButton.repaint();
		};
		colorButton.addActionListener(ae ->{
			chooseColor.run();
		});
		colorButton.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		colorButton.getActionMap().put("enterPressed",rToA(chooseColor));
		
		topPanel.add(textLabel,BorderLayout.WEST);
		topPanel.add(textField,BorderLayout.CENTER);
		//topPanel.add(colorButton,BorderLayout.EAST);
				
		dialog.getContentPane().add(topPanel,BorderLayout.CENTER);
		
		Runnable r = () -> {
		
			StickyNote event = new StickyNote(textField.getText().trim());
			eventCanvas.setSelectedValue(event);
			eventCanvas.repaint();
			dialog.dispose();
		};
		textField.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		textField.getActionMap().put("enterPressed",rToA(r));		
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);

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
		
		final int modulo = 3;
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
				System.out.println("hey");
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
				System.out.println(config);
				
			}
		}
		

		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			//Map<Object,Point> stringPositions = new HashMap<>();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(font.deriveFont(14f));
			FontMetrics metrics = g.getFontMetrics();
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
			int y = metrics.getMaxAscent();
			int rowHeight = metrics.getMaxAscent();
			String titleLabel = "Title:";
			String artistLabel = "Artist:";
			int textFieldX = Stream.of(titleLabel,artistLabel).mapToInt(a->(int) metrics.stringWidth(a)).max().getAsInt();
			int textFieldWidth = metrics.stringWidth("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
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
				int w = groupedByModulo.get(i).stream().mapToInt(a->metrics.stringWidth(a.b.getName())).max().orElse(0)+rowHeight+20;
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
			g.fillRect(0,y-rowHeight,metrics.stringWidth("LOAD"),rowHeight);
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
			
			//KeyStroke k_Esc = KeyStroke.getKeyStroke("ESCAPE");
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
					//System.out.println("loading "+files.get(selectedIndex-2));
					try {
						loadXML(files.get(selectedIndex-2));
						
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
			g.setFont(font.deriveFont(14f));
			FontMetrics metrics = g.getFontMetrics();
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
			int y = metrics.getMaxAscent();
			g.setPaint(Color.RED);
			g.drawString(workingDir.getAbsolutePath(),getWidth()-metrics.stringWidth(workingDir.getAbsolutePath())-2, y);

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
			int w = strings.stream().mapToInt(a->metrics.stringWidth(a.a)).max().getAsInt();
			for (int i = 0; i < strings.size(); i++) {
				Pair<String,Color> p = strings.get(i);
				g.setPaint(selectedIndex == i?Color.DARK_GRAY:Color.black);
				g.fillRect(0, y-metrics.getMaxAscent(), w, metrics.getMaxAscent());
				g.setPaint(p.b);
				g.drawString(p.a, 2, y);
				y+=metrics.getMaxAscent();
			}
			
						
		}
	}
	
	class MainInterfacePanel extends JPanel {
		
		enum SequencePosition {
			SAVE, LOAD, TEMPO, TAPPER, SETTINGS;  
		}
		
		SequencePosition sequencePosition = SequencePosition.TAPPER; 
		boolean isInGrid = false;
		public MainInterfacePanel() {
			this.setFocusTraversalKeysEnabled(false);
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			
			//KeyStroke k_Esc = KeyStroke.getKeyStroke("ESCAPE");
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
			
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
			
			inputMap.put(k_Comma,"comma");
			actionMap.put("comma", rToA(this::comma));
			
			inputMap.put(k_Space,"space");
			actionMap.put("space", rToA(TabbyCat.this::togglePlayStatus));
			
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
		
		void handleCharInput(char c) {
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
				// TODO put this back
				/*
				 * Stream.of(selectedCanvas.get().innerSelectionCells,selectedCanvas.get().
				 * outerSelectionCells) .flatMap(a->a.stream()).forEach(point -> {
				 * 
				 * Optional<?> opt = selectedCanvas.get().getValueAt(point.y,point.x); if
				 * (opt.isPresent()) { if (selectedCanvas.get() instanceof EventCanvas) {
				 * 
				 * eventClipboard.put(point, (ControlEvent) opt.get()); } else if
				 * (selectedCanvas.get() instanceof InstrumentCanvas) {
				 * instrumentClipboard.put(point, (String) opt.get()); } } });
				 * selectedCanvas.get().clearSelectionT0AndRow();
				 */
			}
			isSelectionMode.set(false);
		}
		
		void ctrlX() {
			instrumentClipboard.clear();
			eventClipboard.clear();

			if (isSelectionMode.get()) {
				/*
				 * Stream.of(selectedCanvas.get().innerSelectionCells,selectedCanvas.get().
				 * outerSelectionCells) .flatMap(a->a.stream()).forEach(point -> {
				 * 
				 * Optional<?> opt = selectedCanvas.get().getValueAt(point.y,point.x); if
				 * (opt.isPresent()) { if (selectedCanvas.get() instanceof EventCanvas) {
				 * eventClipboard.put(point, (ControlEvent) opt.get()); } else if
				 * (selectedCanvas.get() instanceof InstrumentCanvas) {
				 * instrumentClipboard.put(point, (String) opt.get()); } }
				 * selectedCanvas.get().removeValueAt(point.y,point.x); });
				 * selectedCanvas.get().clearSelectionT0AndRow();
				 */
			}

			isSelectionMode.set(false);
		}
		
		void ctrlV() {
			
		}
		public void toggleSelectionMode() {

			isSelectionMode.set(!isSelectionMode.get());
			if (isSelectionMode.get()) {
				projectData.getLassoT0().set(projectData.getCursorT().get());;
				projectData.getLassoRow0().set(projectData.getSelectedRow().get());;
				
			} else {
				projectData.getLassoT0().set(-1);
				projectData.getLassoRow0().set(-1);				
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
		
		public final int getMaxVisibleTime() {
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth() / cellWidth;
			int t1 = t0 + tDelta;
			return t1;
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
			switch (dir) {
			case DOWN:
				//projectData.getCursorT().updateAndGet(i->(i+1)%maxRow);
				projectData.getSelectedRow().updateAndGet(i->(i+1)%maxRow);
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
				if (projectData.getCursorT().get() > getMaxVisibleTime() - scrollTimeMargin) {
					projectData.getViewT().getAndIncrement();
				}
				repaint();
				break;
			case UP:
				if (projectData.getSelectedRow().get() == 0) {
					projectData.getSelectedRow().set(maxRow-1);					
				} else {
					projectData.getSelectedRow().getAndDecrement();
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
		
		void enter() {
			switch (sequencePosition) {
			case LOAD:
				break;
			case SAVE:				
				if (activeFile.get() == null) {					
					saveProjectPanel.setFileName(
							String.format("%s.tab",
							DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(LocalDateTime.now(ZoneId.of("Z")))));
					cardLayout.show(cardPanel, saveProjectCardKey);
					
				} else {
					try {
						saveXML(activeFile.get());
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
				break;
			case SETTINGS:
				break;
			case TAPPER:
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

			AtomicReference<GeneralTabCanvas<?>> canvasToSelect = new AtomicReference<>(eventCanvas);
			int maxT = Math.max(projectData.getEventData().keySet().stream().mapToInt(a -> a.x).max().orElse(0),
					projectData.getInstrumentData().keySet().stream().mapToInt(a -> a.getTime()).max().orElse(0));
			setCursorT(maxT);
			projectData.getEventData().keySet().stream().filter(a -> a.x == maxT).findFirst().map(a -> a.y)
					.ifPresent(row -> {
						canvasToSelect.get().setSelectedRow(row);
					});
			projectData.getInstrumentData().keySet().stream().filter(a -> a.getTime() == maxT).findFirst()
					.ifPresent(dataKey -> {
						// TODO put this back
						/*
						 * instrumentCanvases.stream().filter(a->a.getName().equals(dataKey.
						 * getInstrumentName())).findFirst() .ifPresent(canvas -> {
						 * canvasToSelect.set(canvas); canvas.setSelectedRow(dataKey.getRow()); });
						 */
					});

			while (projectData.getCursorT().get() > getMaxVisibleTime() - scrollTimeMargin) {
				projectData.getViewT().getAndIncrement();
			}
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
			int cellWidth = gridMetrics.stringWidth("88")+3;
			int rowHeight = gridMetrics.getMaxAscent()+4;
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth()/cellWidth;
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
			{
				Pair<Integer,Integer> pair = 
						getCanvasNumberAndRelativeRow(projectData.getSelectedRow().get());
				
				int canvasGridNum = pair.a;
				int relativeRow = pair.b;
				
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
			/*
			if (isSelectionMode.get()) {
				Set<Point> outerSelectionCells = new HashSet<>();
				Set<Point> innerSelectionCells = new HashSet<>();
				int sT0 = Math.min(projectData.getCursorT().get(), projectData.getLassoT0().get());
				int sT1 = Math.max(projectData.getCursorT().get(), projectData.getLassoT0().get());
				int row0 = Math.min(projectData.getSelectedRow().get(),  projectData.getLassoRow0().get());
				int row1 = Math.max(projectData.getSelectedRow().get(),  projectData.getLassoRow0().get());
				IntStream.rangeClosed(row0, row1).forEach(row -> {
					outerSelectionCells.add(new Point(row, sT0));
					outerSelectionCells.add(new Point(row, sT1));
				});
				IntStream.rangeClosed(sT0, sT1).forEach(t -> {
					outerSelectionCells.add(new Point(row0, t));
					outerSelectionCells.add(new Point(row1, t));
				});
				
				for (int row = 0; row < getMaxRow(); row++) {
					for (int t = t0; t < t1; t++) {
						Point p = new Point(row, t);
						if ((row == row0 || row == row1) && (t >= sT0 && t <= sT1)) {
							outerSelectionCells.add(p);
						} else if ((t == sT0 || t == sT1) && (row >= row0 && row <= row1)) {
							outerSelectionCells.add(p);
						} else if (row > row0 && row < row1 && t > sT0 && t < sT1) {
							innerSelectionCells.add(p);
						}
					}
				}
		
			}
			*/
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
									
								
									g.setFont(font.deriveFont(Font.ITALIC));
									g.setPaint(Color.YELLOW);
									String text = event.toString();
									g.drawString(text,
											x+(rowHeight-gridMetrics.stringWidth(text))/2,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									g.setFont(font);
									break;
								}
								case PROGRAM_CHANGE: {
									g.setFont(font.deriveFont(Font.ITALIC));
									g.setPaint(new Color(100,200,255));
									String text = event.toString();
									g.drawString(text,
											x+(rowHeight-gridMetrics.stringWidth(text))/2,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
											
									g.setFont(font);
									break;
								}
								case TEMPO: {
									g.setFont(font.deriveFont(Font.ITALIC));
									g.setPaint(new Color(255,200,100));
									String text = event.toString();
									g.drawString(text,
											x+(rowHeight-gridMetrics.stringWidth(text))/2,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
									g.setFont(font);
									break;
								}
								case STICKY_NOTE: {
									g.setFont(font.deriveFont(Font.ITALIC));
									g.setPaint(Color.WHITE);
									String text = ((StickyNote) event).getText();
									g.drawString(text,
											x+(rowHeight-gridMetrics.stringWidth(text))/2,
											(int) (bounds.getMinY()+(row+1)*rowHeight-2));
								}
								default:
									break;					
								}					
							}					
						//	y+=rowHeight;
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
				if (!f.getAbsolutePath().endsWith(".tab")) {
					f = new File(f.getAbsoluteFile() + ".tab");
				}
				try {
					saveXML(f);
				} catch (Exception ex) {
					ex.printStackTrace();					
				}
				activeFile.set(f);
				fileHasBeenModified.set(false);
				
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
					cardLayout.show(cardPanel, mainInterfaceCardKey);
					
				}
					
			}
			
		}
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(font.deriveFont(14f));
			FontMetrics metrics = g.getFontMetrics();
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
						
			int y = metrics.getMaxAscent();
			g.setPaint(Color.RED);
			g.drawString(workingDir.getAbsolutePath(),getWidth()-metrics.stringWidth(workingDir.getAbsolutePath())-2, y);

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
			int w = strings.stream().mapToInt(a->metrics.stringWidth(a.a)).max().getAsInt();
			for (int i = 0; i < strings.size(); i++) {
				Pair<String,Color> p = strings.get(i);
				g.setPaint(selectedIndex == i?Color.DARK_GRAY:Color.black);
				g.fillRect(0, y-metrics.getMaxAscent(), w, metrics.getMaxAscent());
				g.setPaint(p.b);
				g.drawString(p.a, 2, y);
				y+=metrics.getMaxAscent();
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
			g.setPaint(Color.black);
			g.fill(getBounds());
			FontMetrics metrics = g.getFontMetrics();
			String numerLabel = "Numerator:";
			String denomLabel = "Denominator:";
			
			Rectangle2D numerLabelBounds = 
					metrics.getStringBounds(numerLabel, g);
			Rectangle2D numerBounds = 
					metrics.getStringBounds("000", g);
			Rectangle2D denomLabelBounds = 
					metrics.getStringBounds(denomLabel, g);
			Rectangle2D denomBounds = 
					metrics.getStringBounds("16", g);
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
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter,"enter");
			actionMap.put("down", rToA(this::enter));
		}
		void enter() {
			
		}
		void up() {
			tempo = Math.min(1000, tempo-1);
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
			
		}
	}
	
	class NotesEventPanel extends JPanel {
		public NotesEventPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();			
			actionMap.put("enter", rToA(this::enter));
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
		
		void enter() {
			
		}
		
		void handleCharInput(char c) {
			
		}
		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.black);
			g.fill(getBounds());
			
		}
	}

}
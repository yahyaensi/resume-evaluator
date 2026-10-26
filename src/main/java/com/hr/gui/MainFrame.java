package com.hr.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowSorter;
import javax.swing.Scrollable;
import javax.swing.SortOrder;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.github.lgooddatepicker.components.DatePicker;
import com.github.lgooddatepicker.components.DatePickerSettings;
import com.hr.EvaluationResult;
import com.hr.ExcelReportWriter;
import com.hr.GmailApiException;
import com.hr.ResumeSelectorService;
import com.hr.TextExtractor;
import com.hr.auth.GoogleOAuth;
import com.hr.llm.HttpLlmClient;
import com.hr.llm.LlmProvider;
import com.hr.llm.LlmSettings;
import com.hr.llm.LlmSettingsStore;

/** Fenêtre principale : configuration à gauche, classement des candidats et détails à droite. */
public class MainFrame extends JFrame {

    private static final Preferences PREFS = Preferences.userNodeForPackage(MainFrame.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Color GREEN = Color.decode("#16a34a");
    private static final Color ORANGE = Color.decode("#f59e0b");
    private static final Color RED = Color.decode("#ef4444");
    private static final String ACCENT = "#2563eb";
    private static final String AUTHOR_NAME = "Yahya ROMDHANE";
    private static final String AUTHOR_URL = "https://www.linkedin.com/in/yahya-romdhane-ensi";
    /** Date saisie dans le champ « Reçus depuis le » (le jour et le mois peuvent s'écrire avec un seul chiffre). */
    private static final DateTimeFormatter SINCE_FMT =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final int DEFAULT_MAX_EMAILS = 50;

    // --- Champs du formulaire ---
    private final GoogleOAuth oauth = new GoogleOAuth(GoogleOAuth.defaultConfigDir());
    private final JLabel accountLabel = new JLabel();
    private final JLabel accountHint = new JLabel();
    private final JButton connectButton = new JButton("Se connecter avec Google");
    private final JButton logoutButton = new JButton("Se déconnecter");
    // --- Mode d'évaluation ---
    private final JCheckBox keywordModeBox = new JCheckBox("Évaluation par mots-clés (désactive le LLM)");
    private final JLabel keywordInfo = infoIcon("Compare les mots-clés du descriptif de poste au texte des CV : "
            + "gratuit, instantané, sans clé API. Aucun CV n'est envoyé à un service externe.");
    // --- Modèle d'évaluation (LLM) ---
    private final LlmSettingsStore llmStore = new LlmSettingsStore(GoogleOAuth.defaultConfigDir());
    private final JComboBox<LlmProvider> providerBox = new JComboBox<>(LlmProvider.values());
    private final JTextField llmUrlField = new JTextField();
    private final JComboBox<String> llmModelBox = new JComboBox<>();
    private final JPasswordField llmKeyField = new JPasswordField();
    private final JCheckBox rememberKeyBox = new JCheckBox("Mémoriser la clé sur ce poste");
    private final JButton testLlmButton = new JButton("Tester la connexion");
    /** Icône « i » dont l'infobulle (mise à jour selon le fournisseur) dit où part le texte des CV. */
    private final JLabel llmInfo = infoIcon("");
    private boolean loadingLlm;
    private boolean testingLlm;
    private final JComboBox<String> folderBox =
            new JComboBox<>(new String[] { "INBOX", "CV", "[Gmail]/All Mail", "[Gmail]/Tous les messages" });
    private final DatePicker sincePicker = createSincePicker();
    private final JSpinner maxSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_MAX_EMAILS, 1, 5000, 10));
    private final JTextField subjectField = new JTextField();
    private final JTextArea jobArea = new JTextArea(9, 20);
    private final JButton loadJobButton = new JButton("Charger un fichier…");
    private final JButton analyzeButton = new JButton("Analyser les CV");
    private final JButton cancelButton = new JButton("Annuler");

    // --- Résultats ---
    private final ResultsTableModel model = new ResultsTableModel();
    private final JTable table = new JTable(model);
    private final JEditorPane details = new JEditorPane();
    private final JTextArea logArea = new JTextArea();
    private final JButton exportButton = new JButton("Exporter vers Excel…");
    private final JButton themeButton = new JButton();
    private final JButton authorLink = new JButton("<html><u>Développée par " + AUTHOR_NAME + "</u></html>");
    private final JLabel statusLabel = new JLabel("Prêt.");
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    private SwingWorker<ResumeSelectorService.Outcome, String> worker;
    private SwingWorker<GoogleOAuth.Credentials, String> authWorker;
    private ResumeSelectorService.Outcome lastOutcome;
    private String jobLabel = "Descriptif saisi";
    private boolean loadingJob;
    private boolean dark;
    private int formY;
    private BusyGlassPane busyPane;

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            boolean dark = PREFS.getBoolean("dark", false);
            FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", ACCENT));
            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("ProgressBar.arc", 999);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("TabbedPane.showTabSeparators", true);
            // Infobulles plus réactives et assez durables pour lire une phrase complète.
            ToolTipManager.sharedInstance().setInitialDelay(250);
            ToolTipManager.sharedInstance().setDismissDelay(30_000);
            JFrame.setDefaultLookAndFeelDecorated(true);
            new MainFrame(dark).setVisible(true);
        });
    }

    public MainFrame(boolean dark) {
        super("Évaluateur de CV");
        this.dark = dark;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        installAppIcon();
        setSize(1280, 820);
        setMinimumSize(new Dimension(1040, 660));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildLeftPanel(), BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout());
        right.add(new JSeparator(SwingConstants.VERTICAL), BorderLayout.WEST);
        right.add(buildRightPanel(), BorderLayout.CENTER);
        root.add(right, BorderLayout.CENTER);
        setContentPane(root);
        // Voile + spinner pendant l'analyse ; seul « Annuler » reste cliquable.
        busyPane = new BusyGlassPane(root, cancelButton, analyzeButton);
        setGlassPane(busyPane);

        loadPrefs();
        wireActions();
        refreshAuthStatus();
        setRunning(false);
        exportButton.setEnabled(false);
        refreshDetails();
    }

    // =====================================================================
    // Logo / icône de l'application
    // =====================================================================

    private static final BufferedImage LOGO = loadLogo();

    private static BufferedImage loadLogo() {
        try (InputStream in = MainFrame.class.getResourceAsStream("/logo.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /** Réduit l'image par étapes successives (rendu net même pour un très grand logo). */
    private static BufferedImage scaleSmooth(BufferedImage src, int size) {
        BufferedImage current = src;
        int w = src.getWidth();
        int h = src.getHeight();
        while (w > size || h > size) {
            w = Math.max(size, w / 2);
            h = Math.max(size, h / 2);
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        }
        return current;
    }

    /** Icône de la fenêtre / barre des tâches (Windows, Linux) et du Dock (macOS). */
    private void installAppIcon() {
        if (LOGO == null) {
            return;
        }
        setIconImages(List.of(scaleSmooth(LOGO, 16), scaleSmooth(LOGO, 32), scaleSmooth(LOGO, 48),
                              scaleSmooth(LOGO, 64), scaleSmooth(LOGO, 128), scaleSmooth(LOGO, 256)));
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(scaleSmooth(LOGO, 256));
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // pas d'icône de Dock sur cette plateforme : l'icône de fenêtre suffit
        }
    }

    // =====================================================================
    // Construction de l'interface
    // =====================================================================

    private JComponent buildLeftPanel() {
        formY = 0;
        FormPanel form = new FormPanel();
        form.setLayout(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 20, 8, 20));

        JLabel title = new JLabel("Évaluateur de CV");
        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1");
        BufferedImage logo = LOGO;
        if (logo != null) {
            title.setIcon(new ImageIcon(scaleSmooth(logo, 44)));
            title.setIconTextGap(12);
        }
        form.add(title, base(0, 0, 2, 0));

        accountLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold");
        form.add(accountLabel, base(0, 10, 6, 0));
        connectButton.putClientProperty(FlatClientProperties.STYLE, "margin: 7,12,7,12");
        logoutButton.putClientProperty(FlatClientProperties.STYLE, "margin: 7,12,7,12");
        JPanel authButtons = new JPanel(new GridLayout(1, 2, 8, 0));
        authButtons.setOpaque(false);
        authButtons.add(connectButton);
        authButtons.add(logoutButton);
        form.add(authButtons, base(0, 0, 0, 0));
        accountHint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        form.add(accountHint, base(0, 6, 0, 0));

        section(form, "Mode d'évaluation");
        JPanel keywordRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        keywordRow.setOpaque(false);
        keywordRow.add(keywordModeBox);
        keywordRow.add(Box.createHorizontalStrut(5));
        keywordRow.add(keywordInfo);
        form.add(keywordRow, base(0, 0, 0, 0));

        section(form, "Modèle d'évaluation (LLM)", llmInfo);
        field(form, "Fournisseur", providerBox);
        field(form, "URL de l'API", llmUrlField);
        llmModelBox.setEditable(true);
        field(form, "Modèle", llmModelBox);
        field(form, "Clé API", llmKeyField);
        llmKeyField.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true");
        // « Mémoriser la clé » à gauche, « Tester la connexion » à droite : une seule ligne.
        testLlmButton.putClientProperty(FlatClientProperties.STYLE, "margin: 3,8,3,8");
        JPanel rememberRow = new JPanel(new BorderLayout(8, 0));
        rememberRow.setOpaque(false);
        rememberRow.add(rememberKeyBox, BorderLayout.WEST);
        rememberRow.add(testLlmButton, BorderLayout.EAST);
        form.add(rememberRow, base(0, 4, 0, 0));

        section(form, "Recherche des CV");
        folderBox.setEditable(true);
        field(form, "Dossier / libellé Gmail", folderBox);
        field(form, "Reçus depuis le", sincePicker);
        field(form, "Nombre max d'emails (les plus récents)", maxSpinner);
        subjectField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "ex. Candidature (optionnel)");
        subjectField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        field(form, "L'objet contient", subjectField);

        section(form, "Poste à pourvoir");
        JPanel jobHeader = new JPanel(new BorderLayout());
        jobHeader.setOpaque(false);
        jobHeader.add(new JLabel("Descriptif de poste"), BorderLayout.WEST);
        loadJobButton.putClientProperty(FlatClientProperties.STYLE, "buttonType: borderless; foreground: " + ACCENT);
        jobHeader.add(loadJobButton, BorderLayout.EAST);
        form.add(jobHeader, base(0, 6, 3, 0));

        jobArea.setLineWrap(true);
        jobArea.setWrapStyleWord(true);
        jobArea.setRows(5);
        jobArea.setMargin(new Insets(6, 8, 6, 8));
        jobArea.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                                  "Collez ici le descriptif de poste, ou chargez un fichier .txt / .pdf / .docx");
        JScrollPane jobScroll = new JScrollPane(jobArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Hauteur de base modeste : le champ absorbe tout l'espace libre (cf. FormPanel) et c'est lui qui défile
        // quand le texte est long ; la barre de défilement du panneau n'apparaît que si la fenêtre est trop basse.
        jobScroll.setPreferredSize(new Dimension(300, 110));
        // Hauteur minimale garantie : sans ça GridBagLayout peut écraser le champ sur une seule ligne.
        jobScroll.setMinimumSize(new Dimension(300, 110));
        GridBagConstraints jobConstraints = base(0, 0, 0, 0);
        jobConstraints.weighty = 1;
        jobConstraints.fill = GridBagConstraints.BOTH;
        form.add(jobScroll, jobConstraints);

        JScrollPane scroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                             JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);

        analyzeButton.putClientProperty(FlatClientProperties.STYLE,
                                        "background: " + ACCENT + "; foreground: #ffffff; font: bold; margin: 9,16,9,16; "
                                                + "focusedBackground: #1d4ed8; hoverBackground: #1d4ed8; pressedBackground: #1e40af");
        cancelButton.putClientProperty(FlatClientProperties.STYLE, "margin: 9,16,9,16");
        JPanel buttons = new JPanel(new GridLayout(1, 2, 10, 0));
        buttons.setBorder(new EmptyBorder(10, 20, 20, 20));
        buttons.add(analyzeButton);
        buttons.add(cancelButton);

        JPanel left = new JPanel(new BorderLayout());
        left.setPreferredSize(new Dimension(410, 0));
        left.add(scroll, BorderLayout.CENTER);
        left.add(buttons, BorderLayout.SOUTH);
        return left;
    }

    private JComponent buildRightPanel() {
        // Barre du haut
        JLabel heading = new JLabel("Classement des candidats");
        heading.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        actions.setOpaque(false);
        exportButton.putClientProperty(FlatClientProperties.STYLE, "margin: 6,14,6,14");
        themeButton.putClientProperty(FlatClientProperties.STYLE, "margin: 6,14,6,14");
        actions.add(exportButton);
        actions.add(themeButton);
        authorLink.putClientProperty(FlatClientProperties.STYLE,
                                     "buttonType: borderless; foreground: " + ACCENT + "; margin: 0,0,0,0");
        authorLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        authorLink.setToolTipText(AUTHOR_URL);
        authorLink.setFocusable(false);
        authorLink.setAlignmentX(Component.RIGHT_ALIGNMENT);
        actions.setAlignmentX(Component.RIGHT_ALIGNMENT);
        JPanel headerRight = new JPanel();
        headerRight.setOpaque(false);
        headerRight.setLayout(new BoxLayout(headerRight, BoxLayout.Y_AXIS));
        headerRight.add(authorLink);
        headerRight.add(Box.createVerticalStrut(6));
        headerRight.add(actions);
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(new EmptyBorder(14, 20, 12, 20));
        top.add(heading, BorderLayout.WEST);
        top.add(headerRight, BorderLayout.EAST);

        // Tableau
        table.setRowHeight(34);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoCreateRowSorter(true);
        table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
        int[] widths = { 40, 120, 180, 220, 130, 200 };
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(1).setCellRenderer(new ScoreRenderer());
        table.setDefaultRenderer(LocalDateTime.class, new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(value instanceof LocalDateTime d ? DATE_FMT.format(d) : "");
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());

        // Détails + journal
        details.setContentType("text/html");
        details.setEditable(false);
        details.setBorder(new EmptyBorder(12, 16, 12, 16));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setMargin(new Insets(8, 12, 8, 12));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Détails du candidat", new JScrollPane(details));
        tabs.addTab("Journal", new JScrollPane(logArea));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, tabs);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        split.setDividerSize(8);

        // Barre d'état
        progressBar.setPreferredSize(new Dimension(240, 8));
        JPanel progressHolder = new JPanel(new GridBagLayout());
        progressHolder.setOpaque(false);
        progressHolder.add(progressBar);
        JPanel status = new JPanel(new BorderLayout(12, 0));
        status.setBorder(new EmptyBorder(8, 20, 12, 20));
        status.add(statusLabel, BorderLayout.CENTER);
        status.add(progressHolder, BorderLayout.EAST);

        JPanel right = new JPanel(new BorderLayout());
        right.add(top, BorderLayout.NORTH);
        JPanel centerHolder = new JPanel(new BorderLayout());
        centerHolder.setBorder(new EmptyBorder(0, 20, 0, 20));
        centerHolder.add(split, BorderLayout.CENTER);
        right.add(centerHolder, BorderLayout.CENTER);
        right.add(status, BorderLayout.SOUTH);
        return right;
    }

    private void section(JPanel p, String title) {
        section(p, title, null);
    }

    /** Titre de section, précédé d'une icône d'information (infobulle) si {@code info} est fourni. */
    private void section(JPanel p, String title, JComponent info) {
        JLabel titleLabel = new JLabel(title);
        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");
        if (info == null) {
            p.add(titleLabel, base(0, 14, 4, 0));
            return;
        }
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(titleLabel);
        row.add(Box.createHorizontalStrut(5));
        row.add(info);
        p.add(row, base(0, 14, 4, 0));
    }

    private void field(JPanel p, String label, JComponent comp) {
        p.add(new JLabel(label), base(0, 5, 3, 0));
        p.add(comp, base(0, 0, 0, 0));
    }

    private GridBagConstraints base(int top, int topGap, int bottomGap, int unused) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = formY++;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(topGap, 0, bottomGap, 0);
        return c;
    }

    // =====================================================================
    // Actions
    // =====================================================================

    private void wireActions() {
        analyzeButton.addActionListener(e -> startAnalysis());
        connectButton.addActionListener(e -> connectToGoogle());
        logoutButton.addActionListener(e -> logoutFromGoogle());
        providerBox.addActionListener(e -> {
            if (!loadingLlm) {
                LlmProvider p = (LlmProvider) providerBox.getSelectedItem();
                applyLlmSettings(llmStore.load(p));
            }
        });
        testLlmButton.addActionListener(e -> testLlm());
        keywordModeBox.addActionListener(e -> updateLlmControls());
        authorLink.addActionListener(e -> openAuthorPage());
        cancelButton.addActionListener(e -> {
            if (worker != null && !worker.isDone()) {
                worker.cancel(false);
            }
            if (authWorker != null && !authWorker.isDone()) {
                authWorker.cancel(false);
            }
            cancelButton.setEnabled(false);
            statusLabel.setText("Annulation en cours…");
        });
        exportButton.addActionListener(e -> exportExcel());
        themeButton.addActionListener(e -> toggleTheme());
        loadJobButton.addActionListener(e -> chooseJobFile());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshDetails();
            }
        });
        jobArea.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!loadingJob) {
                    jobLabel = "Descriptif saisi";
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        themeButton.setText(dark ? "Thème clair" : "Thème sombre");
    }

    private boolean isBusy() {
        return (worker != null && !worker.isDone()) || (authWorker != null && !authWorker.isDone());
    }

    private void refreshAuthStatus() {
        Optional<String> email = oauth.storedEmail();
        accountLabel.setText(email.map(m -> "Connecté : " + m).orElse("Non connecté"));
        if (oauth.hasClientSecret()) {
            accountHint.setText("<html>Autorisation OAuth2 dans votre navigateur, en lecture seule (gmail.readonly) : "
                    + "aucun mot de passe n'est saisi ni stocké.</html>");
            accountHint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        } else {
            accountHint.setText("<html><b>client_secret.json absent ou invalide dans le jar</b> : placez le fichier "
                    + "(ID client « Application de bureau ») dans src/main/resources puis reconstruisez le jar.</html>");
            accountHint.putClientProperty(FlatClientProperties.STYLE, "foreground: #ef4444");
        }
    }

    private void connectToGoogle() {
        if (isBusy()) {
            return;
        }
        if (!oauth.hasClientSecret()) {
            warn("Le client_secret.json est absent ou invalide dans le jar. Voir le README.");
            return;
        }
        setRunning(true);
        statusLabel.setText("Autorisez l'accès dans votre navigateur…");
        authWorker = new SwingWorker<>() {
            @Override
            protected GoogleOAuth.Credentials doInBackground() throws Exception {
                return oauth.authorize(msg -> publish(msg), this::isCancelled);
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    logArea.append(line + "\n");
                }
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                setRunning(false);
                refreshAuthStatus();
                try {
                    statusLabel.setText("Connecté : " + get().email());
                } catch (CancellationException e) {
                    statusLabel.setText("Connexion annulée.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                    logArea.append("ERREUR : " + msg + "\n");
                    statusLabel.setText("Échec de la connexion Google.");
                    error(msg);
                }
            }
        };
        authWorker.execute();
    }

    private void logoutFromGoogle() {
        if (isBusy()) {
            return;
        }
        if (oauth.storedEmail().isEmpty()) {
            statusLabel.setText("Aucun compte connecté.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                                                   "Révoquer l'autorisation donnée à l'application et supprimer le jeton local ?",
                                                   "Déconnexion", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        statusLabel.setText("Déconnexion…");
        CompletableFuture.runAsync(oauth::logout).whenComplete((v, ex) -> SwingUtilities.invokeLater(() -> {
            refreshAuthStatus();
            statusLabel.setText("Déconnecté.");
        }));
    }

    private void chooseJobFile() {
        JFileChooser chooser = new JFileChooser(PREFS.get("jobDir", System.getProperty("user.home")));
        chooser.setFileFilter(new FileNameExtensionFilter("Descriptif de poste (.txt, .pdf, .docx)",
                                                          "txt", "pdf", "docx"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            String text = TextExtractor.extract(file.getName(), Files.readAllBytes(file.toPath()));
            loadingJob = true;
            jobArea.setText(text.strip());
            loadingJob = false;
            jobArea.setCaretPosition(0);
            jobLabel = file.getName();
            if (file.getParent() != null) {
                PREFS.put("jobDir", file.getParent());
            }
        } catch (Exception ex) {
            loadingJob = false;
            error("Impossible de lire le fichier : " + ex.getMessage());
        }
    }

    private void startAnalysis() {
        if (isBusy()) {
            return;
        }
        String job = jobArea.getText();
        if (!oauth.hasClientSecret()) {
            warn("Le client_secret.json est absent ou invalide dans le jar. Voir le README.");
            return;
        }
        if (job.isBlank()) {
            warn("Collez ou chargez le descriptif de poste.");
            return;
        }
        boolean keywordMode = keywordModeBox.isSelected();
        LlmSettings llm = null;
        if (!keywordMode) {
            try {
                llm = currentLlmSettings();
            } catch (IllegalArgumentException e) {
                warn(e.getMessage());
                return;
            }
        }
        LocalDate since;
        try {
            since = readSinceDate();
        } catch (IllegalArgumentException e) {
            warn(e.getMessage());
            return;
        }
        String folder = String.valueOf(folderBox.getSelectedItem()).trim();
        if (folder.isEmpty() || "null".equals(folder)) {
            folder = "INBOX";
        }
        String subject = subjectField.getText().trim();

        savePrefs(since, keywordMode);
        if (llm != null) {
            saveLlmPrefs(llm);
        }

        ResumeSelectorService.Request request = new ResumeSelectorService.Request(
                                                                                  oauth, job, jobLabel, folder, (Integer) maxSpinner.getValue(),
                                                                                  since,
                                                                                  subject.isEmpty() ? null : subject, null, keywordMode, llm);

        model.setData(List.of());
        lastOutcome = null;
        exportButton.setEnabled(false);
        logArea.setText("");
        refreshDetails();
        setRunning(true);
        busyPane.start();

        worker = new SwingWorker<>() {
            @Override
            protected ResumeSelectorService.Outcome doInBackground() throws Exception {
                return ResumeSelectorService.analyze(request,
                                                     msg -> publish(msg),
                                                     (done, total) -> setProgress(Math.min(100, done * 100 / Math.max(1, total))),
                                                     this::isCancelled);
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    logArea.append(line + "\n");
                }
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                setRunning(false);
                refreshAuthStatus();
                try {
                    ResumeSelectorService.Outcome outcome = get();
                    lastOutcome = outcome;
                    model.setData(outcome.results());
                    if (outcome.results().isEmpty()) {
                        statusLabel.setText("Aucun CV (PDF / Word / texte) trouvé avec ces critères.");
                    } else {
                        double best = outcome.results().get(0).score();
                        long failed = outcome.results().stream().filter(EvaluationResult::hasError).count();
                        statusLabel.setText(String.format("%d CV évalué(s) — meilleur score : %.1f / 100%s",
                                                          outcome.results().size(), best,
                                                          failed > 0 ? " — " + failed + " évaluation(s) en échec (voir le détail)" : ""));
                        exportButton.setEnabled(true);
                        table.setRowSelectionInterval(0, 0);
                    }
                } catch (CancellationException e) {
                    statusLabel.setText("Analyse annulée.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String msg = cause instanceof GmailApiException gmail && gmail.isAuthenticationFailure()
                            ? "Accès à Gmail refusé : l'autorisation a expiré ou été révoquée. Cliquez sur "
                                    + "« Se déconnecter » puis « Se connecter avec Google » pour la renouveler."
                            : (cause.getMessage() != null ? cause.getMessage() : cause.toString());
                    logArea.append("ERREUR : " + msg + "\n");
                    statusLabel.setText("Échec de l'analyse.");
                    error(msg);
                }
            }
        };
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setIndeterminate(false);
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });
        worker.execute();
    }

    private void exportExcel() {
        if (lastOutcome == null || lastOutcome.results().isEmpty()) {
            return;
        }
        JFileChooser chooser = new JFileChooser(PREFS.get("exportDir", System.getProperty("user.home")));
        chooser.setSelectedFile(new File("resultats-cv.xlsx"));
        chooser.setFileFilter(new FileNameExtensionFilter("Classeur Excel (.xlsx)", "xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".xlsx")) {
            file = new File(file.getParentFile(), file.getName() + ".xlsx");
        }
        try {
            ExcelReportWriter.write(file.toPath(), lastOutcome.results(), lastOutcome.jobLabel(),
                                    lastOutcome.modelLabel(), lastOutcome.keywordMode());
            if (file.getParent() != null) {
                PREFS.put("exportDir", file.getParent());
            }
            statusLabel.setText("Excel enregistré : " + file.getAbsolutePath());
            int open = JOptionPane.showConfirmDialog(this,
                                                     "Fichier enregistré :\n" + file.getAbsolutePath() + "\n\nL'ouvrir maintenant ?",
                                                     "Export Excel", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (open == JOptionPane.YES_OPTION && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            }
        } catch (Exception ex) {
            error("Export impossible (le fichier est-il ouvert dans Excel ?) : " + ex.getMessage());
        }
    }

    private void toggleTheme() {
        dark = !dark;
        PREFS.putBoolean("dark", dark);
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        FlatLaf.updateUI();
        themeButton.setText(dark ? "Thème clair" : "Thème sombre");
        refreshDetails();
    }

    private void setRunning(boolean running) {
        analyzeButton.setEnabled(!running);
        connectButton.setEnabled(!running);
        logoutButton.setEnabled(!running);
        keywordModeBox.setEnabled(!running);
        testLlmButton.setEnabled(!running && !keywordModeBox.isSelected());
        cancelButton.setEnabled(running);
        progressBar.setVisible(running);
        if (running) {
            progressBar.setIndeterminate(true);
            progressBar.setValue(0);
            statusLabel.setText("Connexion à Gmail…");
        } else if (busyPane != null) {
            busyPane.stop();
        }
    }

    // =====================================================================
    // Détails du candidat
    // =====================================================================

    private void refreshDetails() {
        int viewRow = table.getSelectedRow();
        String textColor = hex(UIManager.getColor("Label.foreground"));
        if (viewRow < 0) {
            details.setText("<html><body style='font-family:sans-serif;font-size:12px;color:" + textColor
                    + "'>Sélectionnez un candidat pour voir le détail de son évaluation.</body></html>");
            return;
        }
        EvaluationResult r = model.get(table.convertRowIndexToModel(viewRow));
        StringBuilder sb = new StringBuilder("<html><body style='font-family:sans-serif;font-size:12px;color:")
                                                                                                               .append(textColor)
                                                                                                               .append("'>");
        sb.append("<h2 style='margin:0'>").append(esc(r.cv().candidateLabel())).append("</h2>");
        sb.append("<p style='margin:2px 0 10px 0'>")
          .append(esc(r.cv().senderEmail()))
          .append(" · ")
          .append(esc(r.cv().subject()))
          .append(" · <i>")
          .append(esc(r.cv().fileName()))
          .append("</i></p>");
        if (r.hasError()) {
            sb.append("<p style='color:#ef4444'><b>Évaluation échouée :</b> ").append(esc(r.error())).append("</p>");
        } else {
            sb.append(String.format("<p><b>Score %.1f / 100</b></p>", r.score()));
        }
        if (r.cv().text() == null || r.cv().text().isBlank()) {
            sb.append("<p style='color:#ef4444'><b>Aucun texte extrait</b> (PDF scanné ?) — le score n'est pas significatif.</p>");
        }
        if (r.summary() != null && !r.summary().isBlank()) {
            sb.append("<p>").append(esc(r.summary())).append("</p>");
        }
        if (!r.hasError()) {
            appendList(sb, "Points forts", "#16a34a", r.strengths());
            appendList(sb, "Points à vérifier", "#f59e0b", r.gaps());
        }
        sb.append("</body></html>");
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    private static void appendList(StringBuilder sb, String title, String color, List<String> items) {
        sb.append("<p><b style='color:")
          .append(color)
          .append("'>")
          .append(title)
          .append(" (")
          .append(items.size())
          .append(")</b>");
        if (items.isEmpty()) {
            sb.append("<br>—</p>");
            return;
        }
        sb.append("</p><ul style='margin-top:0'>");
        items.forEach(i -> sb.append("<li>").append(esc(i)).append("</li>"));
        sb.append("</ul>");
    }

    private static String hex(Color c) {
        Color color = c == null ? Color.BLACK : c;
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // =====================================================================
    // Préférences et dialogues
    // =====================================================================

    private void loadPrefs() {
        folderBox.setSelectedItem(PREFS.get("folder", "INBOX"));
        keywordModeBox.setSelected(PREFS.getBoolean("keywordMode", false));
        try {
            String since = PREFS.get("since", "");
            if (!since.isBlank()) {
                sincePicker.setDate(LocalDate.parse(since.trim()));
            }
        } catch (DateTimeParseException ignored) {
            // préférence illisible (ancien format) : champ laissé vide
        }
        subjectField.setText(PREFS.get("subject", ""));
        // Clé « maxEmails » (et non « max ») : la valeur par défaut est désormais 50 pour tout le monde.
        maxSpinner.setValue(PREFS.getInt("maxEmails", DEFAULT_MAX_EMAILS));

        LlmProvider last = llmStore.lastProvider();
        loadingLlm = true;
        providerBox.setSelectedItem(last);
        loadingLlm = false;
        applyLlmSettings(llmStore.load(last));
        updateLlmControls();
    }

    // =====================================================================
    // Date de début, lien auteur, mode d'évaluation
    // =====================================================================

    /** Sélecteur de date (calendrier + saisie au clavier), vide par défaut : pas de limite de date. */
    private static DatePicker createSincePicker() {
        DatePickerSettings settings = new DatePickerSettings(Locale.FRENCH);
        settings.setFormatForDatesCommonEra("dd/MM/yyyy");
        settings.setAllowEmptyDates(true);
        settings.setAllowKeyboardEditing(true);
        DatePicker picker = new DatePicker(settings);
        picker.getComponentDateTextField()
              .putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                                 "jj/mm/aaaa (optionnel)");
        return picker;
    }

    /** @return la date choisie, ou null si le champ est vide. @throws IllegalArgumentException si le texte n'est pas une date */
    private LocalDate readSinceDate() {
        String text = sincePicker.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text, SINCE_FMT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                                               "La date « Reçus depuis le » est invalide : choisissez-la dans le calendrier ou saisissez-la "
                                                       + "au format JJ/MM/AAAA (ex. 01/09/2026).");
        }
    }

    private void openAuthorPage() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(AUTHOR_URL));
                return;
            }
        } catch (IOException | RuntimeException ignored) {
            // on affiche l'adresse ci-dessous
        }
        statusLabel.setText("Profil LinkedIn : " + AUTHOR_URL);
    }

    /** Griser la section LLM lorsque l'évaluation par mots-clés est cochée. */
    private void updateLlmControls() {
        boolean llm = !keywordModeBox.isSelected();
        providerBox.setEnabled(llm);
        llmUrlField.setEnabled(llm);
        llmModelBox.setEnabled(llm);
        llmKeyField.setEnabled(llm);
        rememberKeyBox.setEnabled(llm);
        testLlmButton.setEnabled(llm && !isBusy());
    }

    // =====================================================================
    // Modèle d'évaluation (LLM)
    // =====================================================================

    /** Affiche les réglages d'un fournisseur dans le formulaire. */
    private void applyLlmSettings(LlmSettings settings) {
        loadingLlm = true;
        try {
            LlmProvider p = settings.provider();
            providerBox.setSelectedItem(p);
            llmUrlField.setText(settings.baseUrl());
            llmModelBox.setModel(new DefaultComboBoxModel<>(p.suggestedModels().toArray(new String[0])));
            llmModelBox.setSelectedItem(settings.model());
            llmKeyField.setText(settings.apiKey());
            rememberKeyBox.setSelected(!settings.apiKey().isEmpty());
            llmKeyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                                          p.apiKeyRequired() ? "Obligatoire" : "Optionnelle");
            llmUrlField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "https://…");
            String env = p.envVar() == null ? "LLM_API_KEY" : p.envVar();
            llmInfo.setToolTipText(tip(p.isLocal()
                    ? "Modèle exécuté sur votre poste : les CV ne quittent pas votre machine."
                    : "Le texte des CV est envoyé à ce service pour être évalué. La clé peut aussi être "
                            + "fournie par la variable d'environnement " + env + "."));
        } finally {
            loadingLlm = false;
        }
    }

    /** Réglages saisis (la clé vide est complétée par la variable d'environnement, puis par la clé mémorisée). */
    private LlmSettings currentLlmSettings() {
        LlmProvider p = (LlmProvider) providerBox.getSelectedItem();
        // getEditor().getItem() : prend en compte le texte tapé même si le champ n'a pas encore perdu le focus
        Object model = llmModelBox.getEditor().getItem();
        LlmSettings settings = new LlmSettings(p, llmUrlField.getText(), model == null ? "" : model.toString(),
                                               llmStore.resolveKey(p, new String(llmKeyField.getPassword())));
        settings.validate();
        return settings;
    }

    private void saveLlmPrefs(LlmSettings settings) {
        llmStore.save(settings);
        try {
            String typed = new String(llmKeyField.getPassword()).trim();
            if (rememberKeyBox.isSelected() && !typed.isEmpty()) {
                llmStore.saveKey(settings.provider(), typed);
            } else if (!rememberKeyBox.isSelected()) {
                llmStore.deleteKey(settings.provider());
            }
        } catch (IOException e) {
            logArea.append("Clé API non mémorisée : " + e.getMessage() + "\n");
        }
    }

    private void testLlm() {
        if (testingLlm) {
            return;
        }
        LlmSettings settings;
        try {
            settings = currentLlmSettings();
        } catch (IllegalArgumentException e) {
            warn(e.getMessage());
            return;
        }
        // Le bouton reste activé pendant le test : le désactiver alors qu'il a le focus ferait passer celui-ci
        // au champ suivant (« Dossier / libellé Gmail », dont le texte serait alors sélectionné).
        testingLlm = true;
        statusLabel.setText("Test de " + settings.label() + "…");
        CompletableFuture.supplyAsync(() -> {
            try {
                return new HttpLlmClient(settings).complete("Tu es un assistant de test.",
                                                            "Réponds uniquement par le mot OK.");
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).whenComplete((reply, ex) -> SwingUtilities.invokeLater(() -> {
            testingLlm = false;
            if (ex != null) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                statusLabel.setText("Échec du test du modèle.");
                error(cause.getMessage() != null ? cause.getMessage() : cause.toString());
            } else {
                saveLlmPrefs(settings);
                statusLabel.setText("Modèle joignable : " + settings.label());
                JOptionPane.showMessageDialog(this, "Connexion réussie.\nRéponse du modèle : "
                        + (reply.length() > 200 ? reply.substring(0, 200) + "…" : reply.strip()),
                                              "Modèle d'évaluation", JOptionPane.INFORMATION_MESSAGE);
            }
        }));
    }

    /** Aucun secret dans les préférences (jeton OAuth et clé API éventuelle : ~/.resume-selector, droits restreints). */
    private void savePrefs(LocalDate since, boolean keywordMode) {
        PREFS.put("folder", String.valueOf(folderBox.getSelectedItem()));
        PREFS.put("since", since == null ? "" : since.toString());
        PREFS.put("subject", subjectField.getText().trim());
        PREFS.putInt("maxEmails", (Integer) maxSpinner.getValue());
        PREFS.putBoolean("keywordMode", keywordMode);
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "Information manquante", JOptionPane.WARNING_MESSAGE);
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Erreur", JOptionPane.ERROR_MESSAGE);
    }

    // =====================================================================
    // Composants internes
    // =====================================================================

    /** Infobulle HTML à largeur fixe (retour à la ligne automatique). */
    private static String tip(String text) {
        return "<html><p width='280'>" + esc(text) + "</p></html>";
    }

    /** Icône « i » qui affiche {@code tooltip} au survol. */
    private static JLabel infoIcon(String tooltip) {
        JLabel label = new JLabel(new InfoIcon(16));
        label.setToolTipText(tip(tooltip));
        return label;
    }

    /** Pastille « i » dessinée en vectoriel : nette à toutes les échelles, identique en thème clair et sombre. */
    private static class InfoIcon implements Icon {
        private final int size;

        InfoIcon(int size) {
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.decode(ACCENT));
                g2.fill(new Ellipse2D.Double(x, y, size, size));
                g2.setColor(Color.WHITE);
                double dot = size * 0.17;
                double stemW = size * 0.15;
                g2.fill(new Ellipse2D.Double(x + (size - dot) / 2, y + size * 0.21, dot, dot));
                g2.fill(new RoundRectangle2D.Double(x + (size - stemW) / 2, y + size * 0.45, stemW, size * 0.34,
                                                    stemW, stemW));
            } finally {
                g2.dispose();
            }
        }
    }

    /** Panneau de formulaire qui épouse la largeur de la zone de défilement. */
    private static class FormPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            // Si la fenêtre est plus haute que le formulaire, on l'étire (le descriptif prend la place libre) ;
            // sinon on laisse la barre de défilement faire son travail.
            return getParent() instanceof javax.swing.JViewport vp && vp.getHeight() > getPreferredSize().height;
        }
    }

    /** Barre de progression colorée selon le score. */
    private static class ScoreRenderer extends JProgressBar implements TableCellRenderer {
        ScoreRenderer() {
            super(0, 100);
            setStringPainted(true);
            setBorder(BorderFactory.createEmptyBorder(9, 8, 9, 8));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focus, int row, int col) {
            double score = value instanceof Number n ? n.doubleValue() : 0;
            setValue((int) Math.round(score));
            setString(String.format("%.1f", score));
            setForeground(score >= 70 ? GREEN : score >= 40 ? ORANGE : RED);
            setBackground(selected ? t.getSelectionBackground() : t.getBackground());
            return this;
        }
    }

    private static class ResultsTableModel extends AbstractTableModel {
        private static final String[] COLUMNS =
                { "#", "Score", "Candidat", "Email", "Reçu le", "Fichier" };
        private List<EvaluationResult> data = List.of();

        void setData(List<EvaluationResult> results) {
            this.data = List.copyOf(results);
            fireTableDataChanged();
        }

        EvaluationResult get(int index) {
            return data.get(index);
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
            case 0 -> Integer.class;
            case 1 -> Double.class;
            case 4 -> LocalDateTime.class;
            default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            EvaluationResult r = data.get(row);
            return switch (column) {
            case 0 -> row + 1;
            case 1 -> r.score();
            case 2 -> r.cv().candidateLabel();
            case 3 -> r.cv().senderEmail();
            case 4 -> r.cv().receivedAt();
            default -> r.cv().fileName();
            };
        }
    }
}

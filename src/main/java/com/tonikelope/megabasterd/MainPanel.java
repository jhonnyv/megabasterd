package com.tonikelope.megabasterd;

import java.awt.Color;
import static java.awt.EventQueue.invokeLater;
import java.awt.Font;
import static java.awt.Frame.NORMAL;
import java.io.IOException;
import static java.lang.Integer.parseInt;
import static java.lang.System.exit;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import static java.util.concurrent.Executors.newCachedThreadPool;
import java.util.logging.Level;
import static java.util.logging.Level.SEVERE;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import static javax.swing.JOptionPane.QUESTION_MESSAGE;
import static javax.swing.JOptionPane.WARNING_MESSAGE;
import static javax.swing.JOptionPane.YES_NO_CANCEL_OPTION;
import static javax.swing.JOptionPane.showOptionDialog;
import javax.swing.UIManager;
import static com.tonikelope.megabasterd.DBTools.*;
import static com.tonikelope.megabasterd.MiscTools.*;
import static com.tonikelope.megabasterd.Transference.*;
import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import static java.awt.SystemTray.getSystemTray;
import static java.awt.Toolkit.getDefaultToolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import static java.awt.event.WindowEvent.WINDOW_CLOSING;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;

/**
 *
 * @author tonikelope
 */
public final class MainPanel {

    public static final String VERSION = "3.8";
    public static final int THROTTLE_SLICE_SIZE = 16 * 1024;
    public static final int DEFAULT_BYTE_BUFFER_SIZE = 16 * 1024;
    public static final int STREAMER_PORT = 1337;
    public static final int WATCHDOG_PORT = 1338;
    public static final int DEFAULT_MEGA_PROXY_PORT = 9999;
    public static final Font DEFAULT_FONT = createAndRegisterFont("/fonts/Itim-Regular.ttf");
    public static final float ZOOM_FACTOR = 1.0f;
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:57.0) Gecko/20100101 Firefox/57.0";
    public static final String ICON_FILE = "/images/mbasterd_32.png";
    public static final ExecutorService THREAD_POOL = newCachedThreadPool();
    private static String _proxy_host;
    private static int _proxy_port;
    private static Credentials _proxy_credentials;
    private static boolean _use_proxy;
    private static boolean _use_smart_proxy;
    private static String _smart_proxy_url;
    private static SmartMegaProxyManager _proxy_manager;

    public static void main(String args[]) {

        setNimbusLookAndFeel();

        if (args.length > 0) {

            try {
                Logger.getLogger(MainPanel.class.getName()).log(Level.INFO, "{0} Waiting {1} seconds before start...", new Object[]{Thread.currentThread().getName(), args[0]});
                Thread.sleep(Long.parseLong(args[0]) * 1000);
            } catch (InterruptedException ex) {
                Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        final MainPanel main_panel = new MainPanel();

        invokeLater(new Runnable() {
            @Override
            public void run() {
                main_panel.getView().setVisible(true);
            }
        });
    }

    private volatile MainPanelView _view;
    private final GlobalSpeedMeter _global_dl_speed, _global_up_speed;
    private final DownloadManager _download_manager;
    private final UploadManager _upload_manager;
    private final StreamThrottlerSupervisor _stream_supervisor;
    private int _max_dl, _max_ul, _default_slots_down, _default_slots_up, _max_dl_speed, _max_up_speed;
    private boolean _use_slots_down, _use_slots_up, _limit_download_speed, _limit_upload_speed, _use_mega_account_down;
    private String _mega_account_down;
    private String _default_download_path;
    private HashMap<String, Object> _mega_accounts;
    private HashMap<String, Object> _elc_accounts;
    private final HashMap<String, MegaAPI> _mega_active_accounts;
    private TrayIcon _trayicon;
    private final ClipboardSpy _clipboardspy;
    private KissVideoStreamServer _streamserver;
    private byte[] _master_pass;
    private String _master_pass_hash;
    private String _master_pass_salt;
    private boolean _restart;
    private MegaProxyServer _mega_proxy_server;
    private int _megacrypter_reverse_port;
    private boolean _megacrypter_reverse;
    private float _zoom_factor;
    private volatile boolean _exit;

    public MainPanel() {

        _exit = false;

        _restart = false;

        _elc_accounts = new HashMap<>();

        _master_pass = null;

        _mega_active_accounts = new HashMap<>();

        _proxy_host = null;

        _proxy_port = 3128;

        _proxy_credentials = null;

        _use_proxy = false;

        _use_smart_proxy = false;

        _smart_proxy_url = null;

        try {

            setupSqliteTables();

        } catch (SQLException ex) {
            Logger.getLogger(MainPanel.class.getName()).log(SEVERE, null, ex);
        }

        loadUserSettings();

        UIManager.put("OptionPane.messageFont", DEFAULT_FONT.deriveFont(15f * getZoom_factor()));

        UIManager.put("OptionPane.buttonFont", DEFAULT_FONT.deriveFont(13f * getZoom_factor()));

        _view = new MainPanelView(this);

        if (checkAppIsRunning()) {

            System.exit(0);
        }

        try {
            trayIcon();
        } catch (AWTException ex) {
            Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
        }

        THREAD_POOL.execute((_download_manager = new DownloadManager(this)));

        THREAD_POOL.execute((_upload_manager = new UploadManager(this)));

        THREAD_POOL.execute((_global_dl_speed = new GlobalSpeedMeter(_download_manager, getView().getGlobal_speed_down_label(), getView().getDown_remtime_label())));

        THREAD_POOL.execute((_global_up_speed = new GlobalSpeedMeter(_upload_manager, getView().getGlobal_speed_up_label(), getView().getUp_remtime_label())));

        THREAD_POOL.execute((_stream_supervisor = new StreamThrottlerSupervisor(_limit_download_speed ? _max_dl_speed * 1024 : 0, _limit_upload_speed ? _max_up_speed * 1024 : 0, THROTTLE_SLICE_SIZE)));

        THREAD_POOL.execute((_clipboardspy = new ClipboardSpy()));

        try {
            _streamserver = new KissVideoStreamServer(this);
            _streamserver.start(STREAMER_PORT, "/video");
        } catch (IOException ex) {
            Logger.getLogger(MainPanel.class.getName()).log(SEVERE, null, ex);
        }

        THREAD_POOL.execute(new Runnable() {
            @Override
            public void run() {

                String new_version = checkNewVersion("lYsRWaQB", "uVhntmyKcVECRaOxAbcL4A");

                if (new_version != null) {

                    JOptionPane.showMessageDialog(getView(), "MegaBasterd NEW VERSION (" + new_version + ") is available!\n\n(HELP > ABOUT for more info)");
                }
            }
        });

        if (_megacrypter_reverse) {

            _mega_proxy_server = new MegaProxyServer(this, UUID.randomUUID().toString(), _megacrypter_reverse_port);

            THREAD_POOL.execute(_mega_proxy_server);

        } else {
            _mega_proxy_server = null;
        }

        if (_use_smart_proxy) {

            _proxy_manager = new SmartMegaProxyManager(this, _smart_proxy_url);

            THREAD_POOL.execute(_proxy_manager);
        }

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {
                getView().getGlobal_speed_down_label().setForeground(_limit_download_speed ? new Color(255, 0, 0) : new Color(0, 128, 255));

                getView().getGlobal_speed_up_label().setForeground(_limit_upload_speed ? new Color(255, 0, 0) : new Color(0, 128, 255));
            }
        });

        resumeDownloads();

        resumeUploads();
    }

    public boolean isExit() {
        return _exit;
    }

    public void setExit(boolean _exit) {
        this._exit = _exit;
    }

    public float getZoom_factor() {
        return _zoom_factor;
    }

    public void setProxy_manager(SmartMegaProxyManager proxy_manager) {
        _proxy_manager = proxy_manager;
    }

    public static String getProxy_host() {
        return _proxy_host;
    }

    public static int getProxy_port() {
        return _proxy_port;
    }

    public static Credentials getProxy_credentials() {
        return _proxy_credentials;
    }

    public static boolean isUse_proxy() {
        return _use_proxy;
    }

    public static boolean isUse_smart_proxy() {
        return _use_smart_proxy;
    }

    public static String getUse_smart_proxy_url() {
        return _smart_proxy_url;
    }

    public static SmartMegaProxyManager getProxy_manager() {
        return _proxy_manager;
    }

    public MegaProxyServer getMega_proxy_server() {
        return _mega_proxy_server;
    }

    public boolean isMegacrypter_reverse() {
        return _megacrypter_reverse;
    }

    public int getMegacrypter_reverse_port() {
        return _megacrypter_reverse_port;
    }

    public void setMega_proxy_server(MegaProxyServer mega_proxy_server) {
        _mega_proxy_server = mega_proxy_server;
    }

    public boolean isUse_mega_account_down() {
        return _use_mega_account_down;
    }

    public String getMega_account_down() {
        return _mega_account_down;
    }

    public boolean isRestart() {
        return _restart;
    }

    public void setRestart(boolean restart) {
        _restart = restart;
    }

    public HashMap<String, Object> getElc_accounts() {
        return _elc_accounts;
    }

    public TrayIcon getTrayicon() {
        return _trayicon;
    }

    public String getMaster_pass_hash() {
        return _master_pass_hash;
    }

    public void setMaster_pass_hash(String master_pass_hash) {
        _master_pass_hash = master_pass_hash;
    }

    public String getMaster_pass_salt() {
        return _master_pass_salt;
    }

    public byte[] getMaster_pass() {
        return _master_pass;
    }

    public void setMaster_pass(byte[] pass) {

        if (_master_pass != null) {

            Arrays.fill(_master_pass, (byte) 0);

            _master_pass = null;
        }

        if (pass != null) {

            _master_pass = new byte[pass.length];

            System.arraycopy(pass, 0, _master_pass, 0, pass.length);
        }
    }

    public MainPanelView getView() {

        while (_view == null) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ex) {
                Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        return _view;
    }

    public GlobalSpeedMeter getGlobal_dl_speed() {
        return _global_dl_speed;
    }

    public GlobalSpeedMeter getGlobal_up_speed() {
        return _global_up_speed;
    }

    public DownloadManager getDownload_manager() {
        return _download_manager;
    }

    public UploadManager getUpload_manager() {
        return _upload_manager;
    }

    public StreamThrottlerSupervisor getStream_supervisor() {
        return _stream_supervisor;
    }

    public int getMax_dl() {
        return _max_dl;
    }

    public int getMax_ul() {
        return _max_ul;
    }

    public int getDefault_slots_down() {
        return _default_slots_down;
    }

    public int getDefault_slots_up() {
        return _default_slots_up;
    }

    public boolean isUse_slots_down() {
        return _use_slots_down;
    }

    public String getDefault_download_path() {
        return _default_download_path;
    }

    public HashMap<String, Object> getMega_accounts() {
        return _mega_accounts;
    }

    public HashMap<String, MegaAPI> getMega_active_accounts() {
        return _mega_active_accounts;
    }

    public TrayIcon getIcon() {
        return _trayicon;
    }

    public ClipboardSpy getClipboardspy() {
        return _clipboardspy;
    }

    public KissVideoStreamServer getStreamserver() {
        return _streamserver;
    }

    public boolean isUse_slots_up() {
        return _use_slots_up;
    }

    public int getMax_dl_speed() {
        return _max_dl_speed;
    }

    public int getMax_up_speed() {
        return _max_up_speed;
    }

    public boolean isLimit_download_speed() {
        return _limit_download_speed;
    }

    public boolean isLimit_upload_speed() {
        return _limit_upload_speed;
    }

    public void loadUserSettings() {

        String zoom_factor = selectSettingValue("font_zoom");

        if (zoom_factor != null) {
            _zoom_factor = Float.parseFloat(zoom_factor) / 100;
        } else {
            _zoom_factor = ZOOM_FACTOR;
        }

        String def_slots = selectSettingValue("default_slots_down");

        if (def_slots != null) {
            _default_slots_down = parseInt(def_slots);
        } else {
            _default_slots_down = Download.WORKERS_DEFAULT;
        }

        def_slots = selectSettingValue("default_slots_up");

        if (def_slots != null) {
            _default_slots_up = parseInt(def_slots);
        } else {
            _default_slots_up = Upload.WORKERS_DEFAULT;
        }

        String use_slots = selectSettingValue("use_slots_down");

        if (use_slots != null) {
            _use_slots_down = use_slots.equals("yes");
        } else {
            _use_slots_down = Download.USE_SLOTS_DEFAULT;
        }

        use_slots = selectSettingValue("use_slots_up");

        if (use_slots != null) {
            _use_slots_up = use_slots.equals("yes");
        } else {
            _use_slots_up = Upload.USE_SLOTS_DEFAULT;
        }

        String max_downloads = selectSettingValue("max_downloads");

        if (max_downloads != null) {
            _max_dl = parseInt(max_downloads);
        } else {
            _max_dl = Download.SIM_TRANSFERENCES_DEFAULT;
        }

        String max_uploads = selectSettingValue("max_uploads");

        if (max_uploads != null) {
            _max_ul = parseInt(max_uploads);
        } else {
            _max_ul = Upload.SIM_TRANSFERENCES_DEFAULT;
        }

        _default_download_path = selectSettingValue("default_down_dir");

        if (_default_download_path == null) {
            _default_download_path = ".";
        }

        String limit_dl_speed = selectSettingValue("limit_download_speed");

        if (limit_dl_speed != null) {

            _limit_download_speed = limit_dl_speed.equals("yes");

        } else {

            _limit_download_speed = LIMIT_TRANSFERENCE_SPEED_DEFAULT;
        }

        String limit_ul_speed = selectSettingValue("limit_upload_speed");

        if (limit_ul_speed != null) {

            _limit_upload_speed = limit_ul_speed.equals("yes");

        } else {

            _limit_upload_speed = LIMIT_TRANSFERENCE_SPEED_DEFAULT;
        }

        String max_download_speed = selectSettingValue("max_download_speed");

        if (max_download_speed != null) {
            _max_dl_speed = parseInt(max_download_speed);
        } else {
            _max_dl_speed = MAX_TRANSFERENCE_SPEED_DEFAULT;
        }

        String max_upload_speed = selectSettingValue("max_upload_speed");

        if (max_upload_speed != null) {
            _max_up_speed = parseInt(max_upload_speed);
        } else {
            _max_up_speed = MAX_TRANSFERENCE_SPEED_DEFAULT;
        }

        try {
            _mega_accounts = selectMegaAccounts();
            _elc_accounts = selectELCAccounts();
        } catch (SQLException ex) {
            Logger.getLogger(MainPanel.class.getName()).log(SEVERE, null, ex);
        }

        _mega_account_down = DBTools.selectSettingValue("mega_account_down");

        String use_account;

        _use_mega_account_down = ((use_account = DBTools.selectSettingValue("use_mega_account_down")) != null && use_account.equals("yes"));

        _master_pass_hash = DBTools.selectSettingValue("master_pass_hash");

        _master_pass_salt = DBTools.selectSettingValue("master_pass_salt");

        if (_master_pass_salt == null) {

            try {

                _master_pass_salt = Bin2BASE64(genRandomByteArray(CryptTools.PBKDF2_SALT_BYTE_LENGTH));

                DBTools.insertSettingValue("master_pass_salt", _master_pass_salt);

            } catch (SQLException ex) {
                Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        String use_proxy = selectSettingValue("use_proxy");

        if (use_proxy != null) {
            _use_proxy = use_proxy.equals("yes");
        } else {
            _use_proxy = false;
        }

        if (_use_proxy) {

            _proxy_host = DBTools.selectSettingValue("proxy_host");

            String proxy_port = DBTools.selectSettingValue("proxy_port");

            _proxy_port = (proxy_port == null || proxy_port.isEmpty()) ? 8080 : Integer.parseInt(proxy_port);

            String proxy_user = DBTools.selectSettingValue("proxy_user");

            String proxy_pass = DBTools.selectSettingValue("proxy_pass");

            if (proxy_user != null && !proxy_user.isEmpty() && proxy_pass != null) {

                _proxy_credentials = new UsernamePasswordCredentials(proxy_user, proxy_pass);

            } else {

                _proxy_credentials = null;

            }
        }

        String use_megacrypter_reverse = selectSettingValue("megacrypter_reverse");

        if (use_megacrypter_reverse != null) {
            _megacrypter_reverse = use_megacrypter_reverse.equals("yes");
        } else {
            _megacrypter_reverse = false;
        }

        if (_megacrypter_reverse) {

            String reverse_port = DBTools.selectSettingValue("megacrypter_reverse_port");

            _megacrypter_reverse_port = (reverse_port == null || reverse_port.isEmpty()) ? DEFAULT_MEGA_PROXY_PORT : Integer.parseInt(reverse_port);
        }

        String use_smart_proxy = selectSettingValue("smart_proxy");

        if (use_smart_proxy != null) {
            _use_smart_proxy = use_smart_proxy.equals("yes");
        } else {
            _use_smart_proxy = false;
        }

        if (_use_smart_proxy) {

            _smart_proxy_url = selectSettingValue("smart_proxy_url");
        }
    }

    public boolean checkByeBye() {

        boolean exit = true;

        if (!_streamserver.getWorking_threads().isEmpty()) {

            Object[] options = {"No",
                "Yes"};

            int n = showOptionDialog(getView(),
                    "It seems MegaBasterd is streaming video. Do you want to exit?",
                    "Warning!", YES_NO_CANCEL_OPTION, QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]);

            if (n == 0) {

                exit = false;
            }

        } else if (!getDownload_manager().getTransference_provision_queue().isEmpty() || !getUpload_manager().getTransference_provision_queue().isEmpty()) {

            Object[] options = {"No",
                "Yes"};

            int n = showOptionDialog(getView(),
                    "It seems MegaBasterd is provisioning down/uploads.\n\nIf you exit now, unprovisioned down/uploads will be lost.\n\nDo you want to continue?",
                    "Warning!", YES_NO_CANCEL_OPTION, WARNING_MESSAGE,
                    null,
                    options,
                    options[0]);

            if (n == 0) {

                exit = false;
            }

        } else if (!getUpload_manager().getFinishing_uploads_queue().isEmpty()) {

            Object[] options = {"No",
                "Yes"};

            int n = showOptionDialog(getView(),
                    "It seems MegaBasterd is just finishing uploading some files.\n\nIF YOU EXIT NOW, THOSE UPLOADS WILL FAIL.\n\nDo you want to continue?",
                    "Warning!", YES_NO_CANCEL_OPTION, WARNING_MESSAGE,
                    null,
                    options,
                    options[0]);

            if (n == 0) {

                exit = false;
            }
        } else if (!getUpload_manager().getTransference_running_list().isEmpty()) {

            boolean mono = false;

            for (Transference trans : _upload_manager.getTransference_running_list()) {

                if (!((Upload) trans).isUse_slots()) {
                    mono = true;
                    break;
                }
            }

            if (mono) {

                Object[] options = {"No",
                    "Yes"};

                int n = showOptionDialog(getView(),
                        "It seems MegaBasterd is uploading files that can not be safely stopped.\n\nIF YOU EXIT NOW, THOSE UPLOADS WILL BE ABORTED.\n\nDo you want to continue?",
                        "Warning!", YES_NO_CANCEL_OPTION, WARNING_MESSAGE,
                        null,
                        options,
                        options[0]);

                if (n == 0) {

                    exit = false;
                }

            }

        }

        return exit;
    }

    public void byebyenow(boolean restart) {

        synchronized (DBTools.class) {

            try {
                DBTools.vaccum();
            } catch (SQLException ex) {
                Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
            }

            if (restart) {
                restartApplication(1);
            } else {
                exit(0);
            }

        }
    }

    public void byebye(boolean restart) {

        if (!_exit && checkByeBye()) {

            _exit = true;

            getView().getPause_all_down_button().setEnabled(false);

            getView().getPause_all_up_button().setEnabled(false);

            getView().setEnabled(false);

            THREAD_POOL.execute(new Runnable() {
                @Override
                public void run() {

                    boolean wait;

                    do {

                        wait = false;

                        if (!_download_manager.getTransference_running_list().isEmpty()) {

                            for (Transference trans : _download_manager.getTransference_running_list()) {

                                Download download = (Download) trans;

                                if (download.isPaused()) {
                                    download.pause();
                                }

                                if (!download.getChunkworkers().isEmpty()) {

                                    wait = true;

                                    swingInvoke(new Runnable() {
                                        @Override
                                        public void run() {

                                            download.getView().printStatusNormal("Stopping download safely before exit MegaBasterd, please wait...");
                                            download.getView().getSlots_spinner().setEnabled(false);
                                            download.getView().getPause_button().setEnabled(false);
                                            download.getView().getCopy_link_button().setEnabled(false);
                                            download.getView().getOpen_folder_button().setEnabled(false);
                                            download.getView().getFile_size_label().setEnabled(false);
                                            download.getView().getFile_name_label().setEnabled(false);
                                            download.getView().getSpeed_label().setEnabled(false);
                                            download.getView().getSlots_label().setEnabled(false);
                                            download.getView().getProgress_pbar().setEnabled(false);

                                        }
                                    });

                                }
                            }

                        }

                        if (!_upload_manager.getTransference_running_list().isEmpty()) {

                            for (Transference trans : _upload_manager.getTransference_running_list()) {

                                Upload upload = (Upload) trans;

                                if (upload.isUse_slots()) {

                                    upload.getMac_generator().secureNotify();

                                    if (upload.isPaused()) {
                                        upload.pause();
                                    }

                                    if (!upload.getChunkworkers().isEmpty()) {

                                        wait = true;

                                        swingInvoke(new Runnable() {
                                            @Override
                                            public void run() {

                                                upload.getView().printStatusNormal("Stopping upload safely before exit MegaBasterd, please wait...");
                                                upload.getView().getSlots_spinner().setEnabled(false);
                                                upload.getView().getPause_button().setEnabled(false);
                                                upload.getView().getFolder_link_button().setEnabled(false);
                                                upload.getView().getFile_link_button().setEnabled(false);
                                                upload.getView().getFile_size_label().setEnabled(false);
                                                upload.getView().getFile_name_label().setEnabled(false);
                                                upload.getView().getSpeed_label().setEnabled(false);
                                                upload.getView().getSlots_label().setEnabled(false);
                                                upload.getView().getProgress_pbar().setEnabled(false);

                                            }
                                        });
                                    } else {
                                        try {
                                            DBTools.updateUploadProgress(upload.getFile_name(), upload.getMa().getEmail(), upload.getProgress(), upload.getFile_meta_mac() != null ? Bin2BASE64(i32a2bin(upload.getFile_meta_mac())) : null);
                                        } catch (SQLException ex) {
                                            Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
                                        }
                                    }
                                } else {
                                    wait = true;
                                    upload.stopUploader();
                                }
                            }
                        }

                        if (wait) {

                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ex) {
                                Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }

                    } while (wait);

                    byebyenow(restart);
                }
            });

            WarningExitMessage exit_message = new WarningExitMessage(getView(), true, this, restart);

            exit_message.setLocationRelativeTo(getView());

            exit_message.setVisible(true);
        }
    }

    private boolean checkAppIsRunning() {
        boolean app_is_running = false;

        try {

            final ServerSocket serverSocket = new ServerSocket(WATCHDOG_PORT, 0, InetAddress.getLoopbackAddress());

            THREAD_POOL.execute(new Runnable() {

                @Override
                public void run() {

                    final ServerSocket socket = serverSocket;

                    while (true) {

                        try {
                            socket.accept();

                            swingInvoke(
                                    new Runnable() {
                                @Override
                                public void run() {
                                    getView().setExtendedState(NORMAL);

                                    getView().setVisible(true);
                                }
                            });

                        } catch (IOException ex) {
                            Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }

                }
            });

        } catch (IOException ex) {

            app_is_running = true;

            try {

                Socket clientSocket = new Socket(InetAddress.getLoopbackAddress(), WATCHDOG_PORT);

                clientSocket.close();

            } catch (IOException ex1) {

                Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex1);
            }
        }

        return app_is_running;
    }

    private void resumeDownloads() {

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {
                getView().getStatus_down_label().setText("Checking if there are previous downloads, please wait...");
            }
        });

        final MainPanel tthis = this;

        THREAD_POOL.execute(new Runnable() {

            @Override
            public void run() {

                int conta_downloads = 0;

                try {

                    ArrayList<HashMap<String, Object>> res = selectDownloads();

                    for (HashMap<String, Object> o : res) {

                        try {

                            String email = (String) o.get("email");

                            MegaAPI ma;

                            if (!tthis.isUse_mega_account_down() || _mega_accounts.get(email) == null || (ma = checkMegaAccountLoginAndShowMasterPassDialog(tthis, getView(), email)) == null) {

                                ma = new MegaAPI();
                            }

                            Download download = new Download(tthis, ma, (String) o.get("url"), (String) o.get("path"), (String) o.get("filename"), (String) o.get("filekey"), (Long) o.get("filesize"), (String) o.get("filepass"), (String) o.get("filenoexpire"), _use_slots_down, _default_slots_down, false);

                            getDownload_manager().getTransference_provision_queue().add(download);

                            conta_downloads++;

                        } catch (Exception ex) {
                            Logger.getLogger(MainPanel.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }

                } catch (SQLException ex) {

                    Logger.getLogger(MainPanel.class.getName()).log(SEVERE, null, ex);
                }

                if (conta_downloads > 0) {

                    getDownload_manager().secureNotify();

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {
                            getView().getjTabbedPane1().setSelectedIndex(0);
                        }
                    });

                }

                swingInvoke(
                        new Runnable() {
                    @Override
                    public void run() {
                        getView().getStatus_down_label().setText("");
                    }
                });

            }
        });

    }

    public void trayIcon() throws AWTException {

        if (java.awt.SystemTray.isSupported()) {

            PopupMenu menu = new PopupMenu();

            MenuItem messageItem = new MenuItem("Restore window");

            messageItem.addActionListener(new ActionListener() {

                @Override
                public void actionPerformed(ActionEvent e) {

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            getView().setExtendedState(NORMAL);

                            getView().setVisible(true);

                            getView().revalidate();

                            getView().repaint();
                        }
                    });

                }
            });

            menu.add(messageItem);

            MenuItem closeItem = new MenuItem("EXIT");

            closeItem.addActionListener(new ActionListener() {

                @Override
                public void actionPerformed(ActionEvent e) {

                    if (!getView().isVisible()) {

                        getView().setExtendedState(NORMAL);
                        getView().setVisible(true);
                        getView().revalidate();
                        getView().repaint();

                    }

                    byebye(false);

                }

            });

            menu.add(closeItem);

            ActionListener actionListener = new ActionListener() {

                @Override
                public void actionPerformed(ActionEvent e) {

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {

                            if (!getView().isVisible()) {
                                getView().setExtendedState(NORMAL);
                                getView().setVisible(true);
                                getView().revalidate();
                                getView().repaint();

                            } else {

                                getView().dispatchEvent(new WindowEvent(getView(), WINDOW_CLOSING));
                            }
                        }
                    });

                }
            };

            _trayicon = new TrayIcon(getDefaultToolkit().getImage(getClass().getResource(ICON_FILE)), "MegaBasterd", menu);

            _trayicon.setToolTip("MegaBasterd " + VERSION);

            _trayicon.setImageAutoSize(true);

            _trayicon.addActionListener(actionListener);

            getSystemTray().add(_trayicon);

        }

    }

    private void resumeUploads() {

        swingInvoke(
                new Runnable() {
            @Override
            public void run() {
                getView().getStatus_up_label().setText("Checking if there are previous uploads, please wait...");
            }
        });

        final MainPanel tthis = this;

        THREAD_POOL.execute(new Runnable() {
            @Override
            public void run() {

                try {

                    int conta_uploads = 0;

                    ArrayList<HashMap<String, Object>> res = selectUploads();

                    for (HashMap<String, Object> o : res) {

                        try {

                            String email = (String) o.get("email");

                            MegaAPI ma;

                            if (_mega_accounts.get(email) != null) {

                                if ((ma = checkMegaAccountLoginAndShowMasterPassDialog(tthis, getView(), email)) == null) {
                                    ma = new MegaAPI();
                                }

                                Upload upload = new Upload(tthis, ma, (String) o.get("filename"), (String) o.get("parent_node"), (String) o.get("ul_key") != null ? bin2i32a(BASE642Bin((String) o.get("ul_key"))) : null, (String) o.get("url"), (String) o.get("root_node"), BASE642Bin((String) o.get("share_key")), (String) o.get("folder_link"), _use_slots_up, _default_slots_up);

                                getUpload_manager().getTransference_provision_queue().add(upload);

                                conta_uploads++;

                            } else {

                                deleteUpload((String) o.get("filename"), email);
                            }

                        } catch (Exception ex) {
                            Logger.getLogger(MainPanel.class.getName()).log(SEVERE, null, ex);
                        }
                    }

                    if (conta_uploads > 0) {

                        getUpload_manager().secureNotify();

                        swingInvoke(
                                new Runnable() {
                            @Override
                            public void run() {
                                getView().getjTabbedPane1().setSelectedIndex(1);
                            }
                        });

                    }

                    swingInvoke(
                            new Runnable() {
                        @Override
                        public void run() {
                            getView().getStatus_up_label().setText("");
                        }
                    });

                } catch (SQLException ex) {
                    Logger.getLogger(MainPanel.class.getName()).log(SEVERE, null, ex);
                }

            }
        });

    }

}

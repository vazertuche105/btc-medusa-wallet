package com.sparrowwallet.sparrow.io;

import com.google.gson.*;
import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.Theme;
import com.sparrowwallet.sparrow.control.QRDensity;
import com.sparrowwallet.sparrow.control.QREncoding;
import com.sparrowwallet.sparrow.control.WebcamResolution;
import com.sparrowwallet.sparrow.net.*;
import com.sparrowwallet.sparrow.wallet.FeeRatesSelection;
import com.sparrowwallet.sparrow.wallet.OptimizationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

import static com.sparrowwallet.sparrow.AppServices.ENUMERATE_HW_PERIOD_SECS;
import static com.sparrowwallet.sparrow.net.PagedBatchRequestBuilder.DEFAULT_PAGE_SIZE;
import static com.sparrowwallet.sparrow.net.TcpTransport.DEFAULT_MAX_TIMEOUT;
import static com.sparrowwallet.sparrow.wallet.WalletUtxosEntry.DUST_ATTACK_THRESHOLD_SATS;
import static com.sparrowwallet.sparrow.wallet.WalletUtxosEntry.DUST_ATTACK_THRESHOLD_SP_SATS;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public static final String CONFIG_FILENAME = "config";

    /** Perseverus data lives in its OWN file so that running stock Sparrow
     *  (which rewrites config.json from a model without these fields) can
     *  never wipe packs, scan history, or trial state. config.json carries
     *  no perseverus keys at all; this side file is the source of truth. */
    public static final String PERSEVERUS_CONFIG_FILENAME = "perseverus-config";

    private Mode mode;
    private BitcoinUnit bitcoinUnit;
    private UnitFormat unitFormat;
    private Server blockExplorer;
    private FeeRatesSource feeRatesSource;
    private FeeRatesSelection feeRatesSelection;
    private OptimizationStrategy sendOptimizationStrategy;
    private Currency fiatCurrency;
    private ExchangeSource exchangeSource;
    private boolean loadRecentWallets = true;
    private boolean validateDerivationPaths = true;
    private boolean groupByAddress = true;
    private boolean includeMempoolOutputs = true;
    private boolean notifyNewTransactions = true;
    private boolean checkNewVersions = true;
    private Theme theme;
    private boolean openWalletsInNewWindows = false;
    private boolean chunkAddresses = true;
    private boolean hideEmptyUsedAddresses = false;
    private boolean hideAmounts = false;
    private boolean showTransactionHex = true;
    private boolean showLoadingLog = true;
    private boolean showAddressTransactionCount = false;
    private boolean showDeprecatedImportExport = false;
    private boolean signBsmsExports = false;
    private boolean preventSleep = false;
    private Boolean connectToBroadcast;
    private Boolean connectToResolve;
    private Boolean suggestSendToMany;
    private Boolean suggestChangeWalletsDir;
    private File walletsDir;
    private List<File> recentWalletFiles;
    private Integer keyDerivationPeriod;
    private long dustAttackThreshold = DUST_ATTACK_THRESHOLD_SATS;
    private long dustAttackThresholdSp = DUST_ATTACK_THRESHOLD_SP_SATS;
    private int enumerateHwPeriod = ENUMERATE_HW_PERIOD_SECS;
    private QRDensity qrDensity;
    private QREncoding qrEncoding;
    private WebcamResolution webcamResolution;
    private boolean mirrorCapture = true;
    private boolean useZbar = true;
    private String webcamDevice;
    private String webcamDeviceId;
    private ServerType serverType;
    private Server publicElectrumServer;
    private Server coreServer;
    private List<Server> recentCoreServers;
    private CoreAuthType coreAuthType;
    private File coreDataDir;
    private String coreAuth;
    private boolean useLegacyCoreWallet;
    private boolean legacyServer;
    private Server electrumServer;
    private List<Server> recentElectrumServers;
    private File electrumServerCert;
    private boolean useProxy;
    private String proxyServer;
    private boolean autoSwitchProxy = true;
    private int maxServerTimeout = DEFAULT_MAX_TIMEOUT;
    private int maxPageSize = DEFAULT_PAGE_SIZE;
    private boolean usePayNym;
    private String perseverusServerUrl;
    private String perseverusServerPubkey;
    private boolean perseverusDemoMode;
    private List<PersistedPack> perseverusPacks;
    private Map<String, String> perseverusScanResults;
    private Map<String, String> perseverusScanReports;
    private String perseverusScannerUrl;
    private boolean perseverusWelcomed;
    private boolean perseverusTrialMode;
    private int perseverusTrialScansUsed;
    private String perseverusTestnetSpAddress;
    private String perseverusTransportMode;     // "DIRECT", "OHTTP", "TOR"
    private String perseverusOhttpRelayUrl;     // default relay URL for OHTTP
    private boolean perseverusTorEnabled;        // Tor transport enabled
    private int perseverusDecoyCount = 10;       // 0..100 decoys per block (default 10)
    private double perseverusDecoyScale = 1335.0;// Laplace scale; 1335 ≈ ±4000 blocks (4000/ln 20)
    private String perseverusDecoyRange;         // DAY, WEEK, MONTH, YEAR
    private boolean perseverusClearLabelsOnQuit; // wipe KYC tags on app exit
    private boolean perseverusSuppressWelcome;   // skip initial privacy popup
    // Pending BTC payment — survives restart so we can resume polling
    private String perseverusPendingPaymentId;   // SP scanner payment_id
    private String perseverusPendingTxid;         // broadcast txid hex
    private String perseverusPendingPlan;          // "MONTHLY" or "ANNUAL"
    private long   perseverusPendingTimestamp;     // System.currentTimeMillis() at broadcast
    private long   perseverusPendingBlockHeight;   // chain tip when payment was initiated
    private boolean perseverusPendingWatchOnly;   // true if this is a watch-only 2-tx flow
    private String  perseverusPendingTx1Txid;     // txid of the staging tx1 (watch-only)
    private String  perseverusPendingTx2Txid;     // txid of the auto-forwarded tx2 (watch-only)
    private int     perseverusLastStagingIndex = -1; // last used child wallet receive address index
    private long    perseverusPendingQuotedSats;     // subscription sats quoted when the unbroadcast payment was built (for price re-check on relaunch)
    private String  perseverusPaymentProofPubkey;    // BIP-352 proof: 33-byte compressed input pubkey (hex)
    private String  perseverusPaymentProofNonce;     // BIP-352 proof: random nonce (hex)
    private String  perseverusPaymentProofSignature; // BIP-352 proof: DER ECDSA over the claim (hex)
    private boolean mempoolFullRbf;
    private double minRelayFeeRate = Transaction.DEFAULT_MIN_RELAY_FEE;
    private Double appWidth;
    private Double appHeight;

    private static Config INSTANCE;

    private static Gson getGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(File.class, new FileSerializer());
        gsonBuilder.registerTypeAdapter(File.class, new FileDeserializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerSerializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerDeserializer());
        // config.json carries NO perseverus fields: stock Sparrow rewrites
        // config.json from a model that doesn't know them, which used to wipe
        // packs/scan history whenever the user switched between the stock app
        // and this fork. Perseverus data lives in its own side file instead.
        gsonBuilder.setExclusionStrategies(new PerseverusFieldStrategy(true));
        return gsonBuilder.setPrettyPrinting().disableHtmlEscaping().create();
    }

    /** Gson that (de)serializes ONLY the perseverus* fields of Config —
     *  used for the perseverus side file. Nested types (e.g. PersistedPack)
     *  are serialized fully; the filter applies to Config's own fields. */
    private static Gson getPerseverusGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(File.class, new FileSerializer());
        gsonBuilder.registerTypeAdapter(File.class, new FileDeserializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerSerializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerDeserializer());
        gsonBuilder.setExclusionStrategies(new PerseverusFieldStrategy(false));
        return gsonBuilder.setPrettyPrinting().disableHtmlEscaping().create();
    }

    /** Skips (or keeps only) Config's perseverus* fields. Fields of other
     *  classes in the object graph are never filtered. */
    private static class PerseverusFieldStrategy implements ExclusionStrategy {
        private final boolean skipPerseverus;

        private PerseverusFieldStrategy(boolean skipPerseverus) {
            this.skipPerseverus = skipPerseverus;
        }

        @Override
        public boolean shouldSkipField(FieldAttributes f) {
            if(f.getDeclaringClass() != Config.class) {
                return false;
            }
            boolean isPerseverus = f.getName().startsWith("perseverus");
            return skipPerseverus ? isPerseverus : !isPerseverus;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    }

    private static File getConfigFile() {
        File sparrowDir = Storage.getSparrowDir();
        return new File(sparrowDir, CONFIG_FILENAME);
    }

    private static File getPerseverusConfigFile() {
        return new File(Storage.getSparrowDir(), PERSEVERUS_CONFIG_FILENAME);
    }

    private static Config load() {
        Config config = null;
        File configFile = getConfigFile();
        if(configFile.exists()) {
            try {
                Reader reader = new FileReader(configFile);
                config = getGson().fromJson(reader, Config.class);
                reader.close();
            } catch(Exception e) {
                log.error("Error opening " + configFile.getAbsolutePath(), e);
                //Ignore and assume no config
            }
        }

        if(config == null) {
            config = new Config();
        }

        loadPerseverusInto(config);
        return config;
    }

    /** Hydrate the perseverus* fields from the side file. If the side file
     *  does not exist yet, migrate from config.json (older fork versions
     *  stored the fields there) and write the side file immediately so a
     *  subsequent stock-Sparrow session can no longer destroy the data. */
    private static void loadPerseverusInto(Config config) {
        File pFile = getPerseverusConfigFile();
        boolean migrating = !pFile.exists();
        File source = migrating ? getConfigFile() : pFile;
        if(!source.exists()) {
            return;
        }

        try {
            Reader reader = new FileReader(source);
            Config pers = getPerseverusGson().fromJson(reader, Config.class);
            reader.close();
            if(pers != null) {
                copyPerseverusFields(pers, config);
            }
        } catch(Exception e) {
            log.error("Error loading perseverus config from " + source.getAbsolutePath(), e);
            return;
        }

        if(migrating) {
            config.flushPerseverus();
            log.info("Migrated perseverus data from config.json to " + pFile.getName());
        }
    }

    private static void copyPerseverusFields(Config from, Config to) {
        for(java.lang.reflect.Field field : Config.class.getDeclaredFields()) {
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if(!field.getName().startsWith("perseverus")) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(to, field.get(from));
            } catch(Exception e) {
                log.warn("Could not copy perseverus field " + field.getName(), e);
            }
        }
    }

    public static synchronized Config get() {
        if(INSTANCE == null) {
            INSTANCE = load();

        }

        return INSTANCE;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        flush();
    }

    public BitcoinUnit getBitcoinUnit() {
        return bitcoinUnit;
    }

    public void setBitcoinUnit(BitcoinUnit bitcoinUnit) {
        this.bitcoinUnit = bitcoinUnit;
        flush();
    }

    public UnitFormat getUnitFormat() {
        return unitFormat;
    }

    public void setUnitFormat(UnitFormat unitFormat) {
        this.unitFormat = unitFormat;
        flush();
    }

    public boolean isBlockExplorerDisabled() {
        return BlockExplorer.NONE.getServer().equals(blockExplorer);
    }

    public Server getBlockExplorer() {
        return blockExplorer;
    }

    public void setBlockExplorer(Server blockExplorer) {
        this.blockExplorer = blockExplorer;
        flush();
    }

    public FeeRatesSource getFeeRatesSource() {
        return feeRatesSource;
    }

    public void setFeeRatesSource(FeeRatesSource feeRatesSource) {
        this.feeRatesSource = feeRatesSource;
        flush();
    }

    public FeeRatesSelection getFeeRatesSelection() {
        return feeRatesSelection;
    }

    public void setFeeRatesSelection(FeeRatesSelection feeRatesSelection) {
        this.feeRatesSelection = feeRatesSelection;
        flush();
    }

    public OptimizationStrategy getSendOptimizationStrategy() {
        return sendOptimizationStrategy;
    }

    public void setSendOptimizationStrategy(OptimizationStrategy sendOptimizationStrategy) {
        this.sendOptimizationStrategy = sendOptimizationStrategy;
        flush();
    }

    public Currency getFiatCurrency() {
        return fiatCurrency;
    }

    public void setFiatCurrency(Currency fiatCurrency) {
        this.fiatCurrency = fiatCurrency;
        flush();
    }

    public boolean isFetchRates() {
        return getExchangeSource() != ExchangeSource.NONE;
    }

    public ExchangeSource getExchangeSource() {
        return exchangeSource;
    }

    public void setExchangeSource(ExchangeSource exchangeSource) {
        this.exchangeSource = exchangeSource;
        flush();
    }

    public boolean isLoadRecentWallets() {
        return loadRecentWallets;
    }

    public void setLoadRecentWallets(boolean loadRecentWallets) {
        this.loadRecentWallets = loadRecentWallets;
        flush();
    }

    public boolean isValidateDerivationPaths() {
        return validateDerivationPaths;
    }

    public void setValidateDerivationPaths(boolean validateDerivationPaths) {
        this.validateDerivationPaths = validateDerivationPaths;
        flush();
    }

    public boolean isGroupByAddress() {
        return groupByAddress;
    }

    public void setGroupByAddress(boolean groupByAddress) {
        this.groupByAddress = groupByAddress;
        flush();
    }

    public boolean isIncludeMempoolOutputs() {
        return includeMempoolOutputs;
    }

    public void setIncludeMempoolOutputs(boolean includeMempoolOutputs) {
        this.includeMempoolOutputs = includeMempoolOutputs;
        flush();
    }

    public boolean isNotifyNewTransactions() {
        return notifyNewTransactions;
    }

    public void setNotifyNewTransactions(boolean notifyNewTransactions) {
        this.notifyNewTransactions = notifyNewTransactions;
        flush();
    }

    public boolean isCheckNewVersions() {
        return checkNewVersions;
    }

    public void setCheckNewVersions(boolean checkNewVersions) {
        this.checkNewVersions = checkNewVersions;
        flush();
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
        flush();
    }

    public boolean isOpenWalletsInNewWindows() {
        return openWalletsInNewWindows;
    }

    public void setOpenWalletsInNewWindows(boolean openWalletsInNewWindows) {
        this.openWalletsInNewWindows = openWalletsInNewWindows;
        flush();
    }

    public boolean isChunkAddresses() {
        return chunkAddresses;
    }

    public void setChunkAddresses(boolean chunkAddresses) {
        this.chunkAddresses = chunkAddresses;
        flush();
    }

    public boolean isHideEmptyUsedAddresses() {
        return hideEmptyUsedAddresses;
    }

    public void setHideEmptyUsedAddresses(boolean hideEmptyUsedAddresses) {
        this.hideEmptyUsedAddresses = hideEmptyUsedAddresses;
        flush();
    }

    public boolean isHideAmounts() {
        return hideAmounts;
    }

    public void setHideAmounts(boolean hideAmounts) {
        this.hideAmounts = hideAmounts;
        flush();
    }

    public boolean isShowTransactionHex() {
        return showTransactionHex;
    }

    public void setShowTransactionHex(boolean showTransactionHex) {
        this.showTransactionHex = showTransactionHex;
        flush();
    }

    public boolean isShowLoadingLog() {
        return showLoadingLog;
    }

    public void setShowLoadingLog(boolean showLoadingLog) {
        this.showLoadingLog = showLoadingLog;
        flush();
    }

    public boolean isShowAddressTransactionCount() {
        return showAddressTransactionCount;
    }

    public void setShowAddressTransactionCount(boolean showAddressTransactionCount) {
        this.showAddressTransactionCount = showAddressTransactionCount;
        flush();
    }

    public boolean isShowDeprecatedImportExport() {
        return showDeprecatedImportExport;
    }

    public void setShowDeprecatedImportExport(boolean showDeprecatedImportExport) {
        this.showDeprecatedImportExport = showDeprecatedImportExport;
        flush();
    }

    public boolean isSignBsmsExports() {
        return signBsmsExports;
    }

    public void setSignBsmsExports(boolean signBsmsExports) {
        this.signBsmsExports = signBsmsExports;
        flush();
    }

    public boolean isPreventSleep() {
        return preventSleep;
    }

    public void setPreventSleep(boolean preventSleep) {
        this.preventSleep = preventSleep;
        flush();
    }

    public Boolean getConnectToBroadcast() {
        return connectToBroadcast;
    }

    public void setConnectToBroadcast(Boolean connectToBroadcast) {
        this.connectToBroadcast = connectToBroadcast;
        flush();
    }

    public Boolean getConnectToResolve() {
        return connectToResolve;
    }

    public void setConnectToResolve(Boolean connectToResolve) {
        this.connectToResolve = connectToResolve;
        flush();
    }

    public Boolean getSuggestSendToMany() {
        return suggestSendToMany;
    }

    public void setSuggestSendToMany(Boolean suggestSendToMany) {
        this.suggestSendToMany = suggestSendToMany;
        flush();
    }

    public Boolean getSuggestChangeWalletsDir() {
        return suggestChangeWalletsDir;
    }

    public void setSuggestChangeWalletsDir(Boolean suggestChangeWalletsDir) {
        this.suggestChangeWalletsDir = suggestChangeWalletsDir;
        flush();
    }

    public File getWalletsDir() {
        return walletsDir;
    }

    public void setWalletsDir(File walletsDir) {
        this.walletsDir = walletsDir;
        flush();
    }

    public List<File> getRecentWalletFiles() {
        return recentWalletFiles;
    }

    public void setRecentWalletFiles(List<File> recentWalletFiles) {
        this.recentWalletFiles = recentWalletFiles;
        flush();
    }

    public Integer getKeyDerivationPeriod() {
        return keyDerivationPeriod;
    }

    public void setKeyDerivationPeriod(Integer keyDerivationPeriod) {
        this.keyDerivationPeriod = keyDerivationPeriod;
        flush();
    }

    public long getDustAttackThreshold() {
        return dustAttackThreshold;
    }

    public long getDustAttackThresholdSp() {
        return dustAttackThresholdSp;
    }

    public int getEnumerateHwPeriod() {
        return enumerateHwPeriod;
    }

    public QRDensity getQrDensity() {
        return qrDensity == null ? QRDensity.NORMAL : qrDensity;
    }

    public void setQrDensity(QRDensity qrDensity) {
        this.qrDensity = qrDensity;
        flush();
    }

    public QREncoding getQrEncoding() {
        return qrEncoding;
    }

    public void setQrEncoding(QREncoding qrEncoding) {
        this.qrEncoding = qrEncoding;
        flush();
    }

    public WebcamResolution getWebcamResolution() {
        return webcamResolution;
    }

    public void setWebcamResolution(WebcamResolution webcamResolution) {
        this.webcamResolution = webcamResolution;
        flush();
    }

    public boolean isMirrorCapture() {
        return mirrorCapture;
    }

    public void setMirrorCapture(boolean mirrorCapture) {
        this.mirrorCapture = mirrorCapture;
        flush();
    }

    public boolean isUseZbar() {
        return useZbar;
    }

    public String getWebcamDevice() {
        return webcamDevice;
    }

    public void setWebcamDevice(String webcamDevice) {
        this.webcamDevice = webcamDevice;
        flush();
    }

    public String getWebcamDeviceId() {
        return webcamDeviceId;
    }

    public void setWebcamDeviceId(String webcamDeviceId) {
        this.webcamDeviceId = webcamDeviceId;
        flush();
    }

    public ServerType getServerType() {
        return serverType;
    }

    public void setServerType(ServerType serverType) {
        this.serverType = serverType;
        flush();
    }

    public boolean hasServer() {
        return getServer() != null;
    }

    public Server getServer() {
        return getServerType() == ServerType.BITCOIN_CORE ? getCoreServer() : (getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER ? getPublicElectrumServer() : getElectrumServer());
    }

    public String getServerDisplayName() {
        return getServer() == null ? "server" : getServer().getDisplayName();
    }

    public boolean requiresInternalTor() {
        if(isUseProxy()) {
            return false;
        }

        return requiresTor();
    }

    public boolean requiresTor() {
        if(!hasServer()) {
            return false;
        }

        return getServer().isOnionAddress();
    }

    public Server getPublicElectrumServer() {
        return publicElectrumServer;
    }

    public void setPublicElectrumServer(Server publicElectrumServer) {
        this.publicElectrumServer = publicElectrumServer;
        flush();
    }

    public Server getCoreServer() {
        return coreServer;
    }

    public void setCoreServer(Server coreServer) {
        this.coreServer = coreServer;
        flush();
    }

    public List<Server> getRecentCoreServers() {
        return recentCoreServers == null ? new ArrayList<>() : recentCoreServers;
    }

    public boolean addRecentCoreServer(Server coreServer) {
        if(recentCoreServers == null) {
            recentCoreServers = new ArrayList<>();
        }

        int index = getRecentCoreServers().indexOf(coreServer);
        if(index < 0) {
            recentCoreServers.removeIf(server -> server.getHost().equals(coreServer.getHost()) && server.getAlias() == null);
            recentCoreServers.add(coreServer);
            flush();
            return true;
        }

        return false;
    }

    public void removeRecentCoreServer(Server server) {
        int index = getRecentCoreServers().indexOf(server);
        if(index >= 0) {
            recentCoreServers.remove(index);
            flush();
        }
    }

    public void setCoreServerAlias(Server server) {
        int index = getRecentCoreServers().indexOf(server);
        if(index >= 0) {
            recentCoreServers.set(index, server);
            flush();
        }
    }

    public CoreAuthType getCoreAuthType() {
        return coreAuthType;
    }

    public void setCoreAuthType(CoreAuthType coreAuthType) {
        this.coreAuthType = coreAuthType;
        flush();
    }

    public File getCoreDataDir() {
        return coreDataDir;
    }

    public void setCoreDataDir(File coreDataDir) {
        this.coreDataDir = coreDataDir;
        flush();
    }

    public String getCoreAuth() {
        return coreAuth;
    }

    public void setCoreAuth(String coreAuth) {
        this.coreAuth = coreAuth;
        flush();
    }

    public boolean isUseLegacyCoreWallet() {
        return useLegacyCoreWallet;
    }

    public void setUseLegacyCoreWallet(boolean useLegacyCoreWallet) {
        this.useLegacyCoreWallet = useLegacyCoreWallet;
        flush();
    }

    public boolean isLegacyServer() {
        return legacyServer;
    }

    public void setLegacyServer(boolean legacyServer) {
        this.legacyServer = legacyServer;
        flush();
    }

    public Server getElectrumServer() {
        return electrumServer;
    }

    public void setElectrumServer(Server electrumServer) {
        this.electrumServer = electrumServer;
        flush();
    }

    public List<Server> getRecentElectrumServers() {
        return recentElectrumServers == null ? new ArrayList<>() : recentElectrumServers;
    }

    public boolean addRecentServer() {
        if(serverType == ServerType.BITCOIN_CORE && coreServer != null) {
            return addRecentCoreServer(coreServer);
        } else if(serverType == ServerType.ELECTRUM_SERVER && electrumServer != null) {
            return addRecentElectrumServer(electrumServer);
        }

        return false;
    }

    public boolean addRecentElectrumServer(Server electrumServer) {
        if(recentElectrumServers == null) {
            recentElectrumServers = new ArrayList<>();
        }

        int index = getRecentElectrumServers().indexOf(electrumServer);
        if(index < 0) {
            recentElectrumServers.removeIf(server -> server.getHost().equals(electrumServer.getHost()) && server.getAlias() == null);
            recentElectrumServers.add(electrumServer);
            flush();

            return true;
        }

        return false;
    }

    public void removeRecentElectrumServer(Server server) {
        int index = getRecentElectrumServers().indexOf(server);
        if(index >= 0) {
            recentElectrumServers.remove(index);
            flush();
        }
    }

    public void setElectrumServerAlias(Server server) {
        int index = getRecentElectrumServers().indexOf(server);
        if(index >= 0) {
            recentElectrumServers.set(index, server);
            flush();
        }
    }

    public File getElectrumServerCert() {
        return electrumServerCert;
    }

    public void setElectrumServerCert(File electrumServerCert) {
        this.electrumServerCert = electrumServerCert;
        flush();
    }

    public boolean isUseProxy() {
        return useProxy;
    }

    public void setUseProxy(boolean useProxy) {
        this.useProxy = useProxy;
        flush();
    }

    public String getProxyServer() {
        return proxyServer;
    }

    public void setProxyServer(String proxyServer) {
        this.proxyServer = proxyServer;
        flush();
    }

    public boolean isAutoSwitchProxy() {
        return autoSwitchProxy;
    }

    public void setAutoSwitchProxy(boolean autoSwitchProxy) {
        this.autoSwitchProxy = autoSwitchProxy;
        flush();
    }

    public int getMaxServerTimeout() {
        return maxServerTimeout;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public boolean isUsePayNym() {
        return usePayNym;
    }

    public void setUsePayNym(boolean usePayNym) {
        this.usePayNym = usePayNym;
        flush();
    }

    public String getPerseverusServerUrl() {
        return perseverusServerUrl;
    }

    public void setPerseverusServerUrl(String perseverusServerUrl) {
        this.perseverusServerUrl = perseverusServerUrl;
        flush();
    }

    public String getPerseverusServerPubkey() {
        return perseverusServerPubkey;
    }

    public void setPerseverusServerPubkey(String perseverusServerPubkey) {
        this.perseverusServerPubkey = perseverusServerPubkey;
        flush();
    }

    public boolean isPerseverusDemoMode() {
        return perseverusDemoMode;
    }

    public void setPerseverusDemoMode(boolean perseverusDemoMode) {
        this.perseverusDemoMode = perseverusDemoMode;
        flush();
    }

    public List<PersistedPack> getPerseverusPacks() {
        return perseverusPacks == null ? new java.util.ArrayList<>() : perseverusPacks;
    }

    public void setPerseverusPacks(List<PersistedPack> perseverusPacks) {
        this.perseverusPacks = perseverusPacks;
        flush();
    }

    public void addPerseverusPack(PersistedPack pack) {
        if (perseverusPacks == null) {
            perseverusPacks = new java.util.ArrayList<>();
        }
        perseverusPacks.addFirst(pack);
        flush();
    }

    public Map<String, String> getPerseverusScanResults() {
        return perseverusScanResults == null ? new java.util.LinkedHashMap<>() : perseverusScanResults;
    }

    public void setPerseverusScanResults(Map<String, String> perseverusScanResults) {
        this.perseverusScanResults = perseverusScanResults;
        flush();
    }

    public Map<String, String> getPerseverusScanReports() {
        return perseverusScanReports == null ? new java.util.LinkedHashMap<>() : perseverusScanReports;
    }

    public void setPerseverusScanReports(Map<String, String> perseverusScanReports) {
        this.perseverusScanReports = perseverusScanReports;
        flush();
    }

    public String getPerseverusScannerUrl() {
        return perseverusScannerUrl;
    }

    public void setPerseverusScannerUrl(String perseverusScannerUrl) {
        this.perseverusScannerUrl = perseverusScannerUrl;
        flush();
    }

    public boolean isPerseverusWelcomed() {
        return perseverusWelcomed;
    }

    public void setPerseverusWelcomed(boolean perseverusWelcomed) {
        this.perseverusWelcomed = perseverusWelcomed;
        flush();
    }

    public boolean isPerseverusTrialMode() {
        return perseverusTrialMode;
    }

    public void setPerseverusTrialMode(boolean perseverusTrialMode) {
        this.perseverusTrialMode = perseverusTrialMode;
        flush();
    }

    /** True once the user has purchased one or more trial scan tokens. Once set,
     *  the Privacy-tab button reads "BTC Medusa" forever — unless cleared via the
     *  hidden reset hotspot on the trial screen. */
    private boolean perseverusTrialPurchased;

    public boolean isPerseverusTrialPurchased() {
        return perseverusTrialPurchased;
    }

    public void setPerseverusTrialPurchased(boolean perseverusTrialPurchased) {
        this.perseverusTrialPurchased = perseverusTrialPurchased;
        flush();
    }

    public int getPerseverusTrialScansUsed() {
        return perseverusTrialScansUsed;
    }

    public void setPerseverusTrialScansUsed(int perseverusTrialScansUsed) {
        this.perseverusTrialScansUsed = perseverusTrialScansUsed;
        flush();
    }

    public static final int PERSEVERUS_FREE_TRIAL_LIMIT = 3;

    public boolean isPerseverusTrialExhausted() {
        return perseverusTrialMode && perseverusTrialScansUsed >= PERSEVERUS_FREE_TRIAL_LIMIT;
    }

    public int getPerseverusTrialScansRemaining() {
        return Math.max(0, PERSEVERUS_FREE_TRIAL_LIMIT - perseverusTrialScansUsed);
    }

    public String getPerseverusTestnetSpAddress() {
        return perseverusTestnetSpAddress;
    }

    public void setPerseverusTestnetSpAddress(String perseverusTestnetSpAddress) {
        this.perseverusTestnetSpAddress = perseverusTestnetSpAddress;
        flush();
    }

    // ── Privacy Transport Mode ──────────────────────────────────────────

    public enum PerseverusTransport {
        DIRECT("Direct", "No privacy relay — reveals your IP to the BTC Medusa server"),
        OHTTP("OHTTP", "Oblivious HTTP — hides your IP via a relay (coming soon)"),
        TOR("Tor", "Routes through the Tor network for maximum anonymity"),
        AUTO("Auto", "Tor, with automatic fallback (OHTTP fallback coming soon)");

        private final String label;
        private final String description;
        PerseverusTransport(String label, String description) {
            this.label = label;
            this.description = description;
        }
        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    public static final String DEFAULT_OHTTP_RELAY_URL = "https://relay.btcmedusa.com/ohttp";

    public PerseverusTransport getPerseverusTransport() {
        if (perseverusTorEnabled) return PerseverusTransport.TOR;
        if (perseverusTransportMode == null) return PerseverusTransport.AUTO; // default — cascading fallback
        try {
            return PerseverusTransport.valueOf(perseverusTransportMode);
        } catch (IllegalArgumentException e) {
            return PerseverusTransport.AUTO;
        }
    }

    public void setPerseverusTransport(PerseverusTransport mode) {
        this.perseverusTransportMode = mode.name();
        this.perseverusTorEnabled = (mode == PerseverusTransport.TOR || mode == PerseverusTransport.AUTO);
        flush();
    }

    public String getPerseverusOhttpRelayUrl() {
        return perseverusOhttpRelayUrl != null ? perseverusOhttpRelayUrl : DEFAULT_OHTTP_RELAY_URL;
    }

    public void setPerseverusOhttpRelayUrl(String url) {
        this.perseverusOhttpRelayUrl = url;
        flush();
    }

    public boolean isPerseverusTorEnabled() {
        return perseverusTorEnabled;
    }

    public void setPerseverusTorEnabled(boolean enabled) {
        this.perseverusTorEnabled = enabled;
        if (enabled) {
            this.perseverusTransportMode = PerseverusTransport.TOR.name();
        }
        flush();
    }

    public int getPerseverusDecoyCount() {
        return perseverusDecoyCount;
    }

    public void setPerseverusDecoyCount(int count) {
        this.perseverusDecoyCount = Math.max(0, Math.min(100, count));
        flush();
    }

    public double getPerseverusDecoyScale() {
        return perseverusDecoyScale > 0 ? perseverusDecoyScale : 1335.0; // ~±4000 blocks
    }

    public void setPerseverusDecoyScale(double scale) {
        this.perseverusDecoyScale = Math.max(0.1, Math.min(1600.0, scale));
        flush();
    }

    public DecoyRange getPerseverusDecoyRange() {
        if (perseverusDecoyRange == null) return DecoyRange.MONTH; // ~±4000 blocks
        try {
            return DecoyRange.valueOf(perseverusDecoyRange);
        } catch (IllegalArgumentException e) {
            return DecoyRange.MONTH;
        }
    }

    public void setPerseverusDecoyRange(DecoyRange range) {
        this.perseverusDecoyRange = range.name();
        flush();
    }

    /** Decoy spread range presets — controls the Laplace scale slider bounds. */
    public enum DecoyRange {
        DAY("1 Day", "Decoys spread up to ±150 blocks (~1 day)", 1, 150, 15),
        WEEK("1 Week", "Decoys spread ±150–1100 blocks (~1 week)", 150, 1100, 150),
        MONTH("1 Month", "Decoys spread ±1100–4500 blocks (~1 month)", 1100, 4500, 1100),
        YEAR("1 Year", "Decoys spread ±4500–52560 blocks (~1 year)", 4500, 52560, 4500);

        private final String label;
        private final String description;
        private final int sliderMin;
        private final int sliderMax;
        private final int sliderDefault;

        DecoyRange(String label, String description, int sliderMin, int sliderMax, int sliderDefault) {
            this.label = label;
            this.description = description;
            this.sliderMin = sliderMin;
            this.sliderMax = sliderMax;
            this.sliderDefault = sliderDefault;
        }

        public String getLabel() { return label; }
        public String getDescription() { return description; }
        public int getSliderMin() { return sliderMin; }
        public int getSliderMax() { return sliderMax; }
        public int getSliderDefault() { return sliderDefault; }
    }

    public boolean isPerseverusClearLabelsOnQuit() {
        return perseverusClearLabelsOnQuit;
    }

    public void setPerseverusClearLabelsOnQuit(boolean clear) {
        this.perseverusClearLabelsOnQuit = clear;
        flush();
    }

    public boolean isPerseverusSuppressWelcome() {
        return perseverusSuppressWelcome;
    }

    public void setPerseverusSuppressWelcome(boolean suppress) {
        this.perseverusSuppressWelcome = suppress;
        flush();
    }

    // ── Pending BTC payment (survives restart) ─────────────────────────── //

    public String getPerseverusPendingPaymentId() {
        return perseverusPendingPaymentId;
    }

    public String getPerseverusPendingTxid() {
        return perseverusPendingTxid;
    }

    public String getPerseverusPendingPlan() {
        return perseverusPendingPlan;
    }

    public long getPerseverusPendingTimestamp() {
        return perseverusPendingTimestamp;
    }

    public long getPerseverusPendingBlockHeight() {
        return perseverusPendingBlockHeight;
    }

    public boolean hasPerseverusPendingPayment() {
        return (perseverusPendingPaymentId != null && !perseverusPendingPaymentId.isBlank())
                || (perseverusPendingTxid != null && !perseverusPendingTxid.isBlank());
    }

    /**
     * Save pending payment state so polling can resume after restart.
     * Call right after broadcasting the BTC transaction.
     *
     * @param blockHeight the chain tip height at payment time — on restart
     *                    we scan from this height to catch payments that
     *                    confirmed while Sparrow was closed.
     */
    public void setPerseverusPendingPayment(String paymentId, String txid,
                                            String plan, long blockHeight) {
        this.perseverusPendingPaymentId = paymentId;
        this.perseverusPendingTxid = txid;
        this.perseverusPendingPlan = plan;
        this.perseverusPendingTimestamp = System.currentTimeMillis();
        this.perseverusPendingBlockHeight = blockHeight;
        flush();
    }

    /**
     * Clear pending payment state — call after confirmation, token issuance,
     * or when the user explicitly cancels.
     */
    public void clearPerseverusPendingPayment() {
        this.perseverusPendingPaymentId = null;
        this.perseverusPendingTxid = null;
        this.perseverusPendingPlan = null;
        this.perseverusPendingTimestamp = 0;
        this.perseverusPendingBlockHeight = 0;
        this.perseverusPendingWatchOnly = false;
        this.perseverusPendingTx1Txid = null;
        this.perseverusPendingTx2Txid = null;
        this.perseverusPaymentProofPubkey = null;
        this.perseverusPaymentProofNonce = null;
        this.perseverusPaymentProofSignature = null;
        this.perseverusPendingQuotedSats = 0;
        flush();
    }

    /** Subscription sats quoted at the time an (as-yet unbroadcast) watch-only
     *  payment was constructed. Used on relaunch to detect BTC price drift. */
    public void setPerseverusPendingQuotedSats(long sats) {
        this.perseverusPendingQuotedSats = sats;
        flush();
    }

    public long getPerseverusPendingQuotedSats() {
        return perseverusPendingQuotedSats;
    }

    /**
     * BIP-352 proof-of-payment artifacts, computed at payment time (single
     * input) and presented to the scanner's /subscribe after confirmation.
     * All hex: pubkey = 33-byte compressed input key (= a_sum); nonce = random;
     * signature = DER ECDSA over SHA256("perseverus-sp-claim-v1"||pubkey||nonce).
     */
    public void setPerseverusPaymentProof(String pubkeyHex, String nonceHex, String signatureHex) {
        this.perseverusPaymentProofPubkey = pubkeyHex;
        this.perseverusPaymentProofNonce = nonceHex;
        this.perseverusPaymentProofSignature = signatureHex;
        flush();
    }

    public String getPerseverusPaymentProofPubkey() { return perseverusPaymentProofPubkey; }
    public String getPerseverusPaymentProofNonce() { return perseverusPaymentProofNonce; }
    public String getPerseverusPaymentProofSignature() { return perseverusPaymentProofSignature; }

    public boolean hasPerseverusPaymentProof() {
        return perseverusPaymentProofPubkey != null && !perseverusPaymentProofPubkey.isBlank()
                && perseverusPaymentProofNonce != null && !perseverusPaymentProofNonce.isBlank()
                && perseverusPaymentProofSignature != null && !perseverusPaymentProofSignature.isBlank();
    }

    public boolean isPerseverusPendingWatchOnly() {
        return perseverusPendingWatchOnly;
    }

    public void setPerseverusPendingWatchOnly(boolean watchOnly) {
        this.perseverusPendingWatchOnly = watchOnly;
        flush();
    }

    public String getPerseverusPendingTx1Txid() {
        return perseverusPendingTx1Txid;
    }

    public void setPerseverusPendingTx1Txid(String tx1Txid) {
        this.perseverusPendingTx1Txid = tx1Txid;
        flush();
    }

    public int getPerseverusLastStagingIndex() {
        return perseverusLastStagingIndex;
    }

    public void setPerseverusLastStagingIndex(int index) {
        this.perseverusLastStagingIndex = index;
        flush();
    }

    public String getPerseverusPendingTx2Txid() {
        return perseverusPendingTx2Txid;
    }

    public void setPerseverusPendingTx2Txid(String tx2Txid) {
        this.perseverusPendingTx2Txid = tx2Txid;
        flush();
    }

    /**
     * Serializable representation of an issued token pack for persistence.
     * Gson serialises byte[] as Base64 and boolean[] as JSON arrays
     * automatically.
     */
    public static class PersistedPack {
        private int packSize;
        private byte[] blob;
        private boolean[] spent;
        private String issuedAt;

        /** No-arg constructor for Gson. */
        public PersistedPack() {}

        public PersistedPack(int packSize, byte[] blob, boolean[] spent, String issuedAt) {
            this.packSize = packSize;
            this.blob = blob;
            this.spent = spent;
            this.issuedAt = issuedAt;
        }

        public int getPackSize() { return packSize; }
        public byte[] getBlob() { return blob; }
        public boolean[] getSpent() { return spent; }
        public String getIssuedAt() { return issuedAt; }
    }

    public boolean isMempoolFullRbf() {
        return mempoolFullRbf;
    }

    public void setMempoolFullRbf(boolean mempoolFullRbf) {
        this.mempoolFullRbf = mempoolFullRbf;
        flush();
    }

    public double getMinRelayFeeRate() {
        return minRelayFeeRate;
    }

    public void setMinRelayFeeRate(double minRelayFeeRate) {
        this.minRelayFeeRate = minRelayFeeRate;
    }

    public Double getAppWidth() {
        return appWidth;
    }

    public void setAppWidth(Double appWidth) {
        this.appWidth = appWidth;
        flush();
    }

    public Double getAppHeight() {
        return appHeight;
    }

    public void setAppHeight(Double appHeight) {
        this.appHeight = appHeight;
        flush();
    }

    private synchronized void flush() {
        Gson gson = getGson();
        try {
            File configFile = getConfigFile();
            if(!configFile.exists()) {
                Storage.createOwnerOnlyFile(configFile);
            }

            Writer writer = new FileWriter(configFile);
            gson.toJson(this, writer);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            //Ignore
        }

        // Keep the perseverus side file current on every flush. Cheap (small
        // file) and guarantees the side file is always at least as fresh as
        // anything a stock-Sparrow session could later overwrite in config.json.
        flushPerseverus();
    }

    /** Write the perseverus* fields to their own side file (the one stock
     *  Sparrow never touches). See PERSEVERUS_CONFIG_FILENAME. */
    private synchronized void flushPerseverus() {
        try {
            File pFile = getPerseverusConfigFile();
            if(!pFile.exists()) {
                Storage.createOwnerOnlyFile(pFile);
            }

            Writer writer = new FileWriter(pFile);
            getPerseverusGson().toJson(this, writer);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            //Ignore — same policy as flush()
        }
    }

    private static class FileSerializer implements JsonSerializer<File> {
        @Override
        public JsonElement serialize(File src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.getAbsolutePath());
        }
    }

    private static class FileDeserializer implements JsonDeserializer<File> {
        @Override
        public File deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return new File(json.getAsJsonPrimitive().getAsString());
        }
    }

    private static class ServerSerializer implements JsonSerializer<Server> {
        @Override
        public JsonElement serialize(Server src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }
    }

    private static class ServerDeserializer implements JsonDeserializer<Server> {
        @Override
        public Server deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Server.fromString(json.getAsJsonPrimitive().getAsString());
        }
    }
}

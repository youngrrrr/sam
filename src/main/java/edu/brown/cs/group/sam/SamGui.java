package edu.brown.cs.group.sam;

import it.sauronsoftware.jave.EncoderException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.tika.exception.TikaException;
import org.eclipse.jetty.util.ajax.JSON;
import org.xml.sax.SAXException;

import spark.ModelAndView;
import spark.QueryParamsMap;
import spark.Request;
import spark.Response;
import spark.Route;
import spark.Spark;
import spark.TemplateViewRoute;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.vividsolutions.jts.geom.Coordinate;

import edu.brown.cs.group.sam.filesystem.FilesystemViewer;
import edu.brown.cs.group.sam.metadata.MetadataQuery;
import edu.brown.cs.group.sam.metadata.MetadataReader;
import edu.brown.cs.group.sam.metadata.SongInfo;
import edu.brown.cs.group.sam.mp3converter.Mp3Encoder;
import edu.brown.cs.group.sam.panAlgorithm.AmplitudePanner;
import edu.brown.cs.group.sam.panAlgorithm.ClientPoint;
import edu.brown.cs.group.sam.server.MusicServer;
import edu.brown.cs.group.sam.sparkgui.SparkGui;
import edu.brown.cs.yk46.autocorrect.AutocorrectLineRepl;
import edu.brown.cs.yk46.autocorrect.CorpusDataStore;
import edu.brown.cs.yk46.autocorrect.suggestion.LevenshteinEditDistance;
import edu.brown.cs.yk46.autocorrect.suggestion.PrefixMatching;
import edu.brown.cs.yk46.autocorrect.suggestion.SuggestionStrategy;
import edu.brown.cs.yk46.autocorrect.suggestion.Whitespace;

/**
 * Class that extends the basic implementation of a spark graphical user
 * interface.
 *
 * This handles the requests made by the front end for back-end information.
 *
 * @author plscott
 *
 */
public class SamGui extends SparkGui {
  /* a GSON object for communicating with the GUI */
  private static final Gson GSON = new Gson();
  private static final int TIMEOUT = 5; //15 second timeout
  private static final File DEFAULT_DIR_CONFIG_PATH =
      new File("src/main/resources/static/config/defaultdirconfig.txt");
  private static final File AUTOCORRECT_CORPUS =
      new File("src/main/resources/static/autocorrect/songscorpus.txt");
  private static final File DEFAULT_MUSIC_DIR =
      new File("src/main/resources/static/testdirectory");
  private static final List<String> AUDIO_EXTENSIONS = Arrays.asList("4xm",
      "MTV", "RoQ", "aac", "ac3", "aiff", "alaw", "amr", "apc", "ape",
      "asf", "au", "avi", "avs", "bethsoftvid", "c93", "daud", "dsicin",
      "dts", "dv", "dxa", "ea", "ea_cdata", "ffm", "film_cpk", "flac",
      "flic", "flv", "gif", "gxf", "h261", "h263", "h264", "idcin",
      "image2", "image2pipe", "ingenient", "ipmovie", "libnut", "m4v",
      "matroska", "mjpeg", "mm", "mmf", "mov", "mp4", "m4a", "3gp",
      "3g2", "mj2", "mp3", "mpc", "mpc8", "mpeg", "mpegts", "mpegtsraw",
      "mpegvideo", "mulaw", "mxf", "nsv", "nut", "nuv", "ogg", "psxstr",
      "rawvideo", "redir", "rm", "rtsp", "s16be", "s16le", "s8", "sdp",
      "shn", "siff", "smk", "sol", "swf", "thp", "tiertexseq", "tta",
      "txd", "u16be", "u16le", "u8", "vc1", "vmd", "voc", "wav",
      "wc3movie", "wsaud", "wsvqa", "wv", "yuv4mpegpipe");
  private static final int AUTOCORRECT_LED = 2;

  // instance variables declared
  private int port;
  private String serverAddress;
  private static MusicServer server;
  private AmplitudePanner ap;
  private AtomicBoolean mute, noFocus, quickUpdate;
  private AtomicInteger clientId;
  private MetadataQuery mq;
  private ConcurrentHashMap<String, Long> timeoutMap;
  private ConcurrentHashMap<String, Boolean> muteMap;
  private SongInfo[] serverSongs;
  private Map<String, List<SongInfo>> autocorrectSongInfoHM;
  private AutocorrectLineRepl repl;

  public SamGui(int port, String address, String db)
      throws SQLException {
    this.port = port;
    serverAddress = address;
    ap = new AmplitudePanner();
    mute = new AtomicBoolean(false);
    noFocus = new AtomicBoolean(true);
    quickUpdate = new AtomicBoolean(false);
    clientId = new AtomicInteger();
    mq = new MetadataQuery(db);
    timeoutMap = new ConcurrentHashMap<String, Long>();
    muteMap = new ConcurrentHashMap<String, Boolean>();
    serverSongs = new SongInfo[0];
    initAutocorrectValues(serverSongs);
  }

  /**
   * This method runs the spark server at the given port and then sets up get
   * and post requests.
   *
   * @param port The port number at which to run the spark server
   */
  public void runSparkServer() {

    super.runSparkServer(port);

    // set up spark get requests to set up the pages
    Spark.get("/home", new HomeHandler(), super.getEngine());
    Spark.get("/server", new ServerHandler(), super.getEngine());
    Spark.get("/client", new ClientHandler(), super.getEngine());
    Spark.get("/songs", new SongsHandler(), super.getEngine());
    // set up post handlers for interactions with gui
    Spark.post("/startServer", new StartServerHandler());
    Spark.get("/volume", new VolumeHandler(ap, quickUpdate, mute, noFocus, timeoutMap));
    Spark.post("/connectClient", new ConnectClientHandler(clientId, timeoutMap));
    Spark.get("/clients", new ClientPosHandler(ap));
    Spark.post("/updatePosition", new UpdatePosHandler(ap, quickUpdate));
    Spark.post("/mute", new MuteHandler(mute));
    Spark.post("/mp3encode", new Mp3EncodeHandler());
    Spark.post("/chooseMusicDirectory", new MusicDirectoryHandler());
    Spark.post("/changeFocus", new FocusHandler(ap, timeoutMap, noFocus));
    Spark.post("/queryFilesystem", new FilesystemHandler());
    Spark.post("/playSong", new PlaySongHandler());
    Spark.post("/editMetadata", new MetadataHandler());
    Spark.post("/getIP", new IPAddressHandler());
    Spark.post("/search", new SearchHandler());
    Spark.get("/currentDir", new CurrentDirectoryHandler());
  }

  /**
   * Class that models the response when the home page is loaded on the
   * front-end.
   *
   * @author plscott
   *
   */
  private class HomeHandler implements TemplateViewRoute {


    /**
     * Method that handles get requests from the home page on the front-end.
     *
     * @param req the request
     * @param res the response
     */
    @Override
    public ModelAndView handle(Request req, Response res) {
      Map<String, Object> variables =
          new ImmutableMap.Builder<String, Object>().build();

      return new ModelAndView(variables, "home.ftl");
    }
  }

  /**
   * Class that models the response when the server page is loaded on the
   * front-end.
   *
   * @author plscott
   *
   */
  private class ServerHandler implements TemplateViewRoute {
    /**
     * Method that handles get requests from the server page on the front-end.
     *
     * @param req the request
     * @param res the response
     */
    @Override
    public ModelAndView handle(Request req, Response res) {
      Map<String, Object> variables =
          new ImmutableMap.Builder<String, Object>().build();

      return new ModelAndView(variables, "server.ftl");
    }
  }

  /**
   * Class that models the response when the client page is loaded on the
   * front-end.
   *
   * @author plscott
   *
   */
  private class ClientHandler implements TemplateViewRoute {
    /**
     * Method that handles get requests from the client page on the front-end.
     *
     * @param req the request
     * @param res the response
     */
    @Override
    public ModelAndView handle(Request req, Response res) {
      Map<String, Object> variables =
          new ImmutableMap.Builder<String, Object>().build();

      return new ModelAndView(variables, "client.ftl");
    }
  }

  /**
   * Class that models the response when the songs page is loaded on the
   * front-end.
   *
   * @author plscott
   *
   */
  private class SongsHandler implements TemplateViewRoute {
    /**
     * Method that handles get requests from the songs page on the front-end.
     *
     * @param req the request
     * @param res the response
     */
    @Override
    public ModelAndView handle(Request req, Response res) {
      Map<String, Object> variables =
          new ImmutableMap.Builder<String, Object>().build();

      return new ModelAndView(variables, "songs.ftl");
    }
  }

  /**
   * Class that returns output volume for a class
   *
   * @author eselliso
   *
   */
  private static class VolumeHandler implements Route {
    private AmplitudePanner ap;
    private AtomicBoolean quickUpdate, mute, noFocus;
    private Map<String, Long> timeoutMap;

    /**
     * Constructed with ap
     *
     * @param ap - amplitude panner needed
     * @param timeoutMap 
     */
    public VolumeHandler(AmplitudePanner ap, AtomicBoolean quickUpdate, 
        AtomicBoolean mute, AtomicBoolean noFocus, Map<String, Long> timeoutMap) {
      this.ap = ap;
      this.quickUpdate = quickUpdate;
      this.mute = mute;
      this.timeoutMap = timeoutMap;
      this.noFocus = noFocus;
    }

    /**
     * Method that handles get volume output of clients page on the front-end.
     *
     * @param req the request
     * @param res the response
     */
    @Override
    public Object handle(Request req, Response res) {

      QueryParamsMap map = req.queryMap();

      String id = map.value("id");
      long unixTime = System.currentTimeMillis() / 1000L;
      String name = map.value("name");
      if (ap.getClients()!=null && ap.getClients().get(id)!=null) {
        ap.getClients().get(id).setName(name);
      }

      timeoutMap.put(id, unixTime);
      //      timeoutMap.put, value);

      Map<String, ClientPoint> clients = ap.getClients();
      double weight = 1;
      if (ap.getCoordinate() == null) {
        weight = 1;
      } else if (ap.getClients().get(id) == null) {
        weight = 1;
      } else {
        weight = ap.getVolume(id);
      }
      if (Double.isNaN(weight)) {
        weight = 1;
      }
      if (mute.get()) {
        weight = 0;
      }
      Map<String, Object> variables =
          ImmutableMap.of("volume", weight, "quick", quickUpdate.get());
      return GSON.toJson(variables);
    }
  }

  private static class IPAddressHandler implements Route {

    @Override
    public Object handle(Request arg0, Response arg1) {

      String address = "";
      boolean success = true;
      InetAddress ip = null;
      try {
        ip = InetAddress.getLocalHost();			
      } catch (UnknownHostException e) {
        success = false;
      }
      if (success) {
        String[]  addr = ip.getHostAddress().split("/");
        address = addr[addr.length-1];
      }
      Map<String, Object> variables =
          ImmutableMap.of("success", success, "address", address);

      return GSON.toJson(variables);
    }	  
  }

  /**
   * Class that returns all positions of clients
   *
   * @author eselliso
   *
   */
  private static class ClientPosHandler implements Route {
    private AmplitudePanner ap;

    public ClientPosHandler(AmplitudePanner ap) {
      this.ap = ap;
    }

    @Override
    public Object handle(Request request, Response response) {

      Map<String, ClientPoint> allClients = ap.getClients();
      List<HashMap<String, Object>> clientInfo =
          new ArrayList<HashMap<String, Object>>();

      for (ClientPoint c : allClients.values()) {

        HashMap<String, Object> client = new HashMap<String, Object>();        
        client.put("x", c.getPoint().getCoordinate().x);
        client.put("y", c.getPoint().getCoordinate().y);
        client.put("id", c.getId());
        client.put("name", c.getName());
        Double volume = ap.getVolume(c.getId());
        if (volume==null) {
          volume = 0.;
        }
        if (Double.isNaN(volume)) {
          volume = 1.;
        }
        client.put("volume", volume);
        clientInfo.add(client);
      }
      Map<String, Object> variables =
          ImmutableMap.of("clients", clientInfo);

      return GSON.toJson(variables);
    }
  }

  private class ConnectClientHandler implements Route {
    AtomicInteger clientNum;
    Map<String, Long> timeoutMap;

    public ConnectClientHandler(AtomicInteger clientCounter, Map<String, Long> timeoutMap) {
      clientNum = clientCounter;
      this.timeoutMap = timeoutMap;	

    }

    @Override
    public Object handle(Request request, Response response) {
      int clientNumber = clientNum.incrementAndGet();
      String message = "Successful";

      timeoutMap.put(String.valueOf(clientNumber),  (System.currentTimeMillis() / 1000L));
      Map<String, Object> variables =
          new ImmutableMap.Builder<String, Object>()
          .put("message", message).put("id", clientNumber)
          .put("server_url", serverAddress)
          .put("server_port", server.getPort()).put("success", 0).build();
      return GSON.toJson(variables);
    }
  }

  public Object updatePosition(Request request) {
    QueryParamsMap map = request.queryMap();

    String x1 = map.value("x");
    String y1 = map.value("y");
    String id = map.value("id");
    Boolean quick =
        Boolean.parseBoolean(request.queryMap().value("quick"));
    quickUpdate.set(quick);
    String name = request.queryMap().value("name");
    Double x = Double.parseDouble(x1);
    Double y = Double.parseDouble(y1);
    double[] pos = { x, y };
    ClientPoint client = ap.getClients().get(id);
    if (client != null) {
      ap.removeClient(client);
    }
    client = new ClientPoint(pos, id, 1);
    client.setName(name);

    ap.addClient(client);
    String message = "Success";
    if (id.equals("0")) {
      return currentInfo();
    }   
    Map<String, Object> variables =
        ImmutableMap.of("message", message, "success", 0);
    return GSON.toJson(variables);
  }

  /**
   * Class to handle updating of position
   *
   * @author eselliso
   *
   */
  private class UpdatePosHandler implements Route {
    AmplitudePanner ap;
    AtomicBoolean quickUpdate;

    /**
     * Instantiated withh reference to the Amplitude Panner
     *
     * @param ap
     */
    public UpdatePosHandler(AmplitudePanner ap, AtomicBoolean quickUpdate) {
      this.quickUpdate = quickUpdate;
      this.ap = ap;
    }

    @Override
    public Object handle(Request request, Response response) {

      return updatePosition(request);
    }
  }
  private class MuteHandler implements Route {

    AtomicBoolean mute;

    public MuteHandler(AtomicBoolean mute) {
      this.mute = mute;
    }

    @Override
    public Object handle(Request request, Response response) {
      mute.set(!mute.get());
      Map<String, Object> variables = ImmutableMap.of("message", "success");
      return GSON.toJson(variables);
    }
  }
  /**
   * Handles changing focus
   *
   * @author eselliso
   *
   */

  public Object currentInfo() {
    String message = "Success";
    List<HashMap<String, Object>> clientInfo =
        new ArrayList<HashMap<String, Object>>();
    Map<String, ClientPoint> allClients = ap.getClients();
    for (ClientPoint c : allClients.values()) {

      HashMap<String, Object> client = new HashMap<String, Object>();
      Double xc = c.getPoint().getCoordinate().x;
      if (xc == null) {
        xc = -50.;
      }
      Double yc = c.getPoint().getCoordinate().y;
      if (yc == null) {
        yc = -50.;
      }
      client.put("x", xc);
      client.put("y", yc);
      client.put("id", c.getId());
      Double volume = ap.getVolume(c.getId());
      if (volume == null) {
        volume = 0.0;
      }
      if (Double.isNaN(volume)) {
        volume = 1.;
      }
      if (noFocus.get()) {
        volume = 1.0;
      }
      if (mute.get()) { 
        volume = 0.;
      }	
      for (String s: muteMap.keySet()) {
    	  System.out.println(s);
    	  System.out.println(muteMap.get(s));
      }      
      System.out.println("timeoutMap");
      for (String s: timeoutMap.keySet()) {
    	  System.out.println(s);
      }
      
      System.out.println("map");
      if (muteMap != null && c!=null && c.getId()!=null) {
    	  System.out.println("map2");
    	  if (muteMap.get(c.getId())!=null && muteMap.get(c.getId())) {
    		  System.out.println("map key");
    		  System.out.println(c.getId());
        	  volume = 0.;
    	  }
      }
      client.put("volume", volume);
      client.put("name", c.getName());
      clientInfo.add(client);
    }

    Map<String, Object> variables =
        ImmutableMap.of("message", message, "success", 0, "clients", clientInfo);
    return GSON.toJson(variables);

  }

  private class FocusHandler implements Route {
    AmplitudePanner ap;
    AtomicBoolean noFocus;
    Map<String, Long> timeoutMap;

    /**
     * Instantiated with reference to the Amplitude Panner
     *
     * @param ap
     * @param noFocus 
     */
    public FocusHandler(AmplitudePanner ap, Map<String, Long> timeoutMap, AtomicBoolean noFocus) {
      this.ap = ap;
      this.timeoutMap = timeoutMap;
      this.noFocus = noFocus;
    }

    @Override
    public Object handle(Request request, Response response) {
      QueryParamsMap map = request.queryMap();

      updatePosition(request);
      Boolean noFocusB = Boolean.parseBoolean(map.value("pause"));
      noFocus.set(noFocusB);
      Boolean muted = Boolean.parseBoolean(map.value("mute"));
      mute.set(muted);
      String fociString = map.value("focusPoints");
      System.out.println("here");
      String muteString = map.value("muteArray");
      System.out.println(muteString);
      if (muteString!=null && muteString.trim().length()!=0) { 
    	  String[] var = muteString.split("key:");
    	  for (String s: var) {
    		  s.trim();
    		  String[] split = s.split(",");
    		  if (split.length >= 2) {
        		  String id = split[0].trim();
        		  Boolean mutedV = Boolean.parseBoolean(split[1].trim());
        		  muteMap.put(id, mutedV);
    		  }
    	  }
      }
      
      Set<Coordinate> pointSet = new HashSet<Coordinate>();
      if (fociString.lastIndexOf(",") != -1) {
        fociString = fociString.substring(0, fociString.lastIndexOf(","));
        String[] pointA = fociString.split(",");
        for (int i=0; i<=pointA.length-1; i+=2) {
          int x = Integer.parseInt(pointA[i].trim());
          int y = Integer.parseInt(pointA[i+1].trim());
          Coordinate newC = new Coordinate(x, y);
          pointSet.add(newC);
        }
      }
      ap.calcluteVolume(pointSet);      
      for (String s: timeoutMap.keySet()) {
        if (((System.currentTimeMillis() / 1000L) - timeoutMap.get(s)) > TIMEOUT) {
          timeoutMap.remove(s);
          ap.removeClient(s);
        }
      }
      return currentInfo();
    }
  }

  /**
   * Class that handles the gui request to start the music server.
   *
   * @author plscott
   *
   */
  private class StartServerHandler implements Route {
    @Override
    public Object handle(Request req, Response res) {
      if (server == null) {
        server = new MusicServer(serverAddress);
        server.run();
      }

      Map<String, Object> variables =
          new ImmutableMap.Builder<String, Object>()
          .put("socket_url", serverAddress)
          .put("socket_port", server.getPort()).build();

      return GSON.toJson(variables);
    }
  }

  /**
   * Route for encoding most audio files to .mp3.
   *
   * @author yk46
   *
   */
  private class Mp3EncodeHandler implements Route {

    @Override
    public Object handle(Request req, Response res) {
      QueryParamsMap qm = req.queryMap();
      String song = GSON.fromJson(qm.value("filePath"), String.class);

      try {
        return GSON.toJson(Mp3Encoder.encode(song));
      } catch (IllegalArgumentException | EncoderException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }

      return song;
    }

  }

  /**
   * Grabs and loads all metadata info for the songs in the given directory.
   *
   * @author young-raekim
   *
   */
  private class MusicDirectoryHandler implements Route {

    @Override
    public Object handle(Request req, Response res) {
      QueryParamsMap qm = req.queryMap();
      String musicDirectoryPath = qm.value("dir");
      File musicDirectory = new File(musicDirectoryPath);

      SamGui.emptyAndOverwriteFile(SamGui.DEFAULT_DIR_CONFIG_PATH,
          musicDirectoryPath);

      if (musicDirectory.getName().equals("")) {
        musicDirectory = new File(System.getProperty("user.home"));
      }

      File[] files = musicDirectory.listFiles();

      List<SongInfo> songs =
          getSongInfoFromFlattenedDirectory(files, new ArrayList<>());

      serverSongs = songs.toArray(new SongInfo[songs.size()]);

      initAutocorrectValues(serverSongs);

      return GSON.toJson(serverSongs);
    }

  }

  private class FilesystemHandler implements Route {

    @Override
    public Object handle(Request req, Response res) {
      QueryParamsMap qm = req.queryMap();
      String path = qm.value("path");

      if (path == null) {
        path = "";
      }

      FilesystemViewer viewer = new FilesystemViewer(path);
      return GSON.toJson(viewer);
    }

  }

  private static class PlaySongHandler implements Route {

    @Override
    public Object handle(Request req, Response res) {
      QueryParamsMap qm = req.queryMap();
      String songPath = qm.value("songPath");
      File song = new File(songPath);

      String[] fileNameArr = song.getName().split("\\.");
      String fileType = "";

      if (fileNameArr.length > 1) {
        fileType = fileNameArr[1];
      }

      // now that we have the song play it
      server.setMusicFile(song);
      server.broadcast();
      int songId = server.getCurrentSongId().get();

      Map<String, Object> variables =
          new ImmutableMap.Builder<String, Object>()
          .put("song_id", songId).build();

      return GSON.toJson(variables);
    }

  }

  private class MetadataHandler implements Route {

    @Override
    public Object handle(Request req, Response res) {
      QueryParamsMap qm = req.queryMap();
      String filePath = qm.value("filePath");
      String title = qm.value("title");
      String album = qm.value("album");
      String artist = qm.value("artist");

      try {
        mq.insertOrReplaceIntoSongInfo(filePath, title, album, artist);
      } catch (SQLException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }

      return null;
    }

  }

  private class SearchHandler implements Route {

    @Override
    public Object handle(Request req, Response res) {
      QueryParamsMap qm = req.queryMap();
      String searchInput = qm.value("line");

      if (searchInput == null || searchInput.equals("")) {
        return GSON.toJson(serverSongs);
      }

      String[] autocorrectResults = repl.evaluateInput(searchInput).split("\n");

      List<SongInfo> songInfos = new ArrayList<>();
      for (String autocorrectResult : autocorrectResults) {
        songInfos.addAll(autocorrectSongInfoHM.getOrDefault(autocorrectResult, new ArrayList<>()));
      }

      HashSet<SongInfo> songInfosSet = new HashSet<>(songInfos);

      return GSON.toJson(songInfosSet.toArray(new SongInfo[songInfosSet.size()]));
    }

  }

  private class CurrentDirectoryHandler implements Route {

    @Override
    public Object handle(Request request, Response response) {
      try (BufferedReader r = new BufferedReader(
          new FileReader(DEFAULT_DIR_CONFIG_PATH))) {
        File defaultDir = new File(r.readLine());

        if (defaultDir.exists()) {
          return defaultDir.getAbsolutePath();
        } else {
          File iTunesDir = new File(getITunesDirectoryPath());
          if (!iTunesDir.getName().equals("")) { //exists
            return iTunesDir.getAbsolutePath();
          } else {
            return DEFAULT_MUSIC_DIR.getAbsolutePath();
          }
        }
      } catch (Exception e) {
        File iTunesDir = new File(getITunesDirectoryPath());
        if (!iTunesDir.getName().equals("")) { //exists
          return iTunesDir.getAbsolutePath();
        } else {
          return DEFAULT_MUSIC_DIR.getAbsolutePath();
        }
      }
    }

  }

  private String getITunesDirectoryPath() {
    File userDir = new File(System.getProperty("user.home"));
    File[] userSubDirs = userDir.listFiles();

    for (File subDir : userSubDirs) {
      if (subDir.getName().contains("Music")) {
        List<File> musicSubDirsList = (List<File>) FileUtils.listFilesAndDirs(
            subDir, FalseFileFilter.FALSE, TrueFileFilter.TRUE);
        for (File musicSubDir : musicSubDirsList) {
          if (musicSubDir.getName().equalsIgnoreCase("iTunes")) {
            return musicSubDir.getAbsolutePath();
          }
        }
      }
    }

    return "";
  }

  private List<SongInfo> getSongInfoFromFlattenedDirectory(File[] files,
      List<SongInfo> songs) {
    for (File f : files) {
      if (f.isDirectory()) {
        songs.addAll(getSongInfoFromFlattenedDirectory(f.listFiles(),
            new ArrayList<>()));
      }

      String[] fileNameArr = f.getName().split("\\.");
      String fileType = "";

      if (fileNameArr.length > 1) {
        fileType = fileNameArr[fileNameArr.length - 1];
      }

      if (AUDIO_EXTENSIONS.contains(fileType)) {
        SongInfo si;
        try {
          si = mq.getSongInfo(f);
        } catch (IOException | SAXException | TikaException
            | SQLException e1) {
          si = getMissingSongInfo(f);
        }

        if (si.getFilePath() == null) {
          si = getMissingSongInfo(f);
        }

        songs.add(si);
      }
    }

    return songs;
  }

  private SongInfo getMissingSongInfo(File f) {
    SongInfo si;
    try {
      si = MetadataReader.getAudioMetadata(f);
    } catch (IOException | SAXException | TikaException e) {
      si = new SongInfo(f.getAbsolutePath());
    }

    try {
      mq.insertOrReplaceSongInfo(si);
    } catch (SQLException e) {
      // TODO Auto-generated catch block
    }

    return si;
  }

  private void initAutocorrectValues(SongInfo[] songs) {
    String corpusStr = "";
    autocorrectSongInfoHM = new HashMap<>();

    for (SongInfo si : songs) {
      String title = si.getTitle();
      String album = si.getAlbum();
      String artist = si.getArtist();
      String filename = new File(si.getFilePath()).getName();
      String[] basePhrases = {title, album, artist, filename};

      corpusStr += title + " " + album + " " + artist + " " + filename + "\n";

      List<String> sequenceCombinations = getSequenceCombinations(basePhrases);
      for (String sequenceCombination: sequenceCombinations) {
        List<SongInfo> matchedSongs = autocorrectSongInfoHM.getOrDefault(
            sequenceCombination, new ArrayList<>());
        matchedSongs.add(si);
        autocorrectSongInfoHM.put(sequenceCombination, matchedSongs);
      }
    }

    emptyAndOverwriteFile(AUTOCORRECT_CORPUS, corpusStr);

    CorpusDataStore dataStore = new CorpusDataStore();
    try {
      dataStore.readFile(SamGui.AUTOCORRECT_CORPUS);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    List<SuggestionStrategy> strategies = new ArrayList<>();
    strategies.add(new PrefixMatching(dataStore.getTrie()));
    strategies.add(new LevenshteinEditDistance(dataStore.getTrie(), AUTOCORRECT_LED));
    strategies.add(new Whitespace(dataStore.getTrie()));

    repl = new AutocorrectLineRepl(strategies, dataStore.getNgramFreqMap(), false);
  }

  private static List<String> getSequenceCombinations(String[] basePhrases) {
    List<String> sequenceCombinations = new ArrayList<>();

    for (String basePhrase : basePhrases) {
      if (basePhrase == null) {
        continue;
      }

      String[] basePhraseArr = basePhrase.toLowerCase().split(
          CorpusDataStore.PUNCTUATION_WHITESPACE_REGEX);
      String sequenceCombination = basePhraseArr[0];
      sequenceCombinations.add(sequenceCombination);
      for (int i = 1; i < basePhraseArr.length; i++) {
        sequenceCombination += " " + basePhraseArr[i];
        sequenceCombinations.add(sequenceCombination);
      }
    }

    return sequenceCombinations;
  }

  private static void emptyAndOverwriteFile(File file, String content) {
    try (FileWriter fw = new FileWriter(file, false)) {
      fw.write(content);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  public void shutdown() {
    server.close();
  }
}
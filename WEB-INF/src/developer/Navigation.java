package developer;

import java.util.Calendar;
import java.util.Date;
import java.util.Vector;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import developer.image.OpenCVObjectDetect;
import oculusPrime.Application;
import oculusPrime.AutoDock;
import oculusPrime.AutoDock.autodockmodes;
import oculusPrime.FrameGrabHTTP;
import oculusPrime.GUISettings;
import oculusPrime.ManualSettings;
import oculusPrime.Observer;
import oculusPrime.PlayerCommands;
import oculusPrime.Settings;
import oculusPrime.State;
import oculusPrime.State.values;
import oculusPrime.SystemWatchdog;
import oculusPrime.Util;
import oculusPrime.commport.ArduinoPrime;

public class Navigation implements Observer {
	
	public static final long WAYPOINTTIMEOUT = Util.FIVE_MINUTES;
	public static final long NAVSTARTTIMEOUT = Util.TWO_MINUTES;
	public static final int RESTARTAFTERCONSECUTIVEROUTES = 20;  // TODO: set to 15 in production
	public static final int MIN_BATTERY_LIFE = 50;               // TODO: CALCULATE THIS FROM ROUTE INFO?
	public static final String DOCK = "dock";                    // waypoint name
	
	private static Settings settings = Settings.getReference();
	private static State state = State.getReference();
	public static boolean navdockactive = false;
	public static boolean failed = false; 
	private static Application app = null;	
	private static Element navroute = null;
	public static long routemillimeters = 0;  
	public static long routestarttime = 0;
	public static int consecutiveroute = 1;  
//	public static int rotations = 0;

	
	/** Constructor */
	public Navigation(Application a){
		app = a;
		state.set(values.navsystemstatus, Ros.navsystemstate.stopped.name());
		Ros.loadwaypoints();
		Ros.rospackagedir = Ros.getRosPackageDir(); // required for map saving
		state.addObserver(this);
	}
	  
	@Override
	public void updated(String key){
		
		if(key.equals(values.distanceangle.name())){
			try {
				int mm = Integer.parseInt(state.get(values.distanceangle).split(" ")[0]);
				if(mm > 0) routemillimeters += mm;
			} catch (Exception e){}
		}
		
//		if(key.equals(values.recoveryrotation.name())){
//			if(state.getBoolean(values.recoveryrotation)) rotations++; 
//			Util.log("rotations: " + rotations, this);
//		}
		
		if(key.equals(values.roswaypoint.name())){
			Util.log("point: " + state.get(values.roswaypoint), this);
		}
		
		
		if(key.equals(values.navigationroute.name())){
			Util.log("NAV ROUTE: " + state.get(values.navigationroute), this);
		}
		
		if(key.equals(values.dockstatus.name())){
			if(state.equals(values.dockstatus, AutoDock.DOCKED)){
				
				Util.log("docked, resetiing..", this);	
				 
				state.delete(values.routeoverdue);
				state.delete(values.recoveryrotation);		
//				rotations = 0;
				failed = false;
			
			//  never do these here! 
			//	routemillimeters = 0;  
			//	routestarttime = 0;
			//	estimatedmeters = 0;	      
			//	estimatedtime = 0;	
				
			}
		}
	}

	/** */
	public static boolean batteryTooLow(){
		
		if(state.equals(values.dockstatus, AutoDock.UNDOCKED)) return false;
		
		long start = System.currentTimeMillis();
		String current;
		int value = 0;
		
		while(true){	
			current = state.get(values.batterylife); 
			if(current!=null) if(current.contains("%")) break;
			Util.delay(100);
			
			if(System.currentTimeMillis()-start > 10000){ 
				Util.debug("batteryTooLow(): timeout, assume too low");
				return true;
			}
		}
	
		try {
			value = Integer.parseInt(current.split("%")[0]);
			// Util.log("battery value: " + value, null);
		} catch (NumberFormatException e) {
			Util.log("batteryTooLow(): can't read battery info from state", null);
			return true;
		}
		
		if(value < MIN_BATTERY_LIFE){
			app.driverCallServer(PlayerCommands.messageclients, "skipping route, battery too low");
			return true; 
		}
		
		return false;
	}
	
	/** */
	public static void gotoWaypoint(final String str){
		
		if(state.getBoolean(values.autodocking)){
			app.driverCallServer(PlayerCommands.messageclients, "command dropped, autodocking");
			return;
		}

		if (state.equals(values.dockstatus, AutoDock.UNDOCKED) &&
				!state.equals(values.navsystemstatus, Ros.navsystemstate.running.name())){
			app.driverCallServer(PlayerCommands.messageclients, "Can't navigate, location unknown");
			return;
		}
		
		new Thread(new Runnable(){ public void run(){

			if( ! waitForNavSystem()) return;
			
			// undock if necessary
			if(!state.equals(values.dockstatus, AutoDock.UNDOCKED)) undockandlocalize();
			
			if(!Ros.setWaypointAsGoal(str))
				app.driverCallServer(PlayerCommands.messageclients, "unable to set waypoint");

		}}).start();
	}
	
	/** blocking */
	private static boolean waitForNavSystem(){ 

		if (state.equals(values.navsystemstatus, Ros.navsystemstate.mapping.name()) ||
				state.equals(values.navsystemstatus, Ros.navsystemstate.stopping.name())) {
			app.driverCallServer(PlayerCommands.messageclients, "Navigation.waitForNavSystem(): can't start navigation");
			return false;
		}

		startNavigation();
		state.block(values.navsystemstatus, Ros.navsystemstate.running.name(), (int)NAVSTARTTIMEOUT*3, 300);
	
		if (!state.equals(values.navsystemstatus, Ros.navsystemstate.running.name())){
			app.driverCallServer(PlayerCommands.messageclients, "Navigation.waitForNavSystem(): navigation start failure");
			return false;
		}

		return true;
	}

	/** */
	public static void startMapping(){
		
		if (!state.equals(values.navsystemstatus, Ros.navsystemstate.stopped.name())) {
			app.driverCallServer(PlayerCommands.messageclients, "Navigation.startMapping(): unable to start mapping, system already running");
			return;
		}

		if (!Ros.launch(Ros.MAKE_MAP)) {
			app.driverCallServer(PlayerCommands.messageclients, "roslaunch already running, aborting mapping start");
			return;
		}

		app.driverCallServer(PlayerCommands.messageclients, "starting mapping, please wait");
		state.set(values.navsystemstatus, Ros.navsystemstate.starting.name()); // set running by ROS node when ready
		app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.med.name());
		Ros.launch(Ros.MAKE_MAP);
	}

	/** */
	public static void startNavigation(){
		if (!state.equals(values.navsystemstatus, Ros.navsystemstate.stopped)) return;
		new Thread(new Runnable(){ public void run(){
			app.driverCallServer(PlayerCommands.messageclients, "starting navigation, please wait");
			if (!Ros.launch(Ros.REMOTE_NAV)){
				app.driverCallServer(PlayerCommands.messageclients, "roslaunch already running, abort");
				return;
			}
			
			state.set(values.navsystemstatus, Ros.navsystemstate.starting.name()); // set running by ROS node when ready
			state.block(values.navsystemstatus, Ros.navsystemstate.running.name(), (int)NAVSTARTTIMEOUT, 50);
			
			if (state.equals(values.navsystemstatus, Ros.navsystemstate.running)){
				if (settings.getBoolean(ManualSettings.useflash))
					app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.med.name()); // reduce cpu
				if (!state.equals(values.dockstatus, AutoDock.UNDOCKED))
					state.set(values.rosinitialpose, "0_0_0");
				Util.log("navigation running", this);
				return; // success
			}

			//======== try again if needed, just once ======
			
			// in case cancelled
			if (state.equals(values.navsystemstatus, Ros.navsystemstate.stopping.name()) ||
					state.equals(values.navsystemstatus, Ros.navsystemstate.stopped.name())) return; 
			
			Util.log("navigation start attempt #2", this);
			stopNavigation();
			state.block(values.navsystemstatus, Ros.navsystemstate.stopped.name(), (int)NAVSTARTTIMEOUT, 50);
			if( ! Ros.launch(Ros.REMOTE_NAV)){
				app.driverCallServer(PlayerCommands.messageclients, "roslaunch already running, abort");
				return;
			}

			state.block(values.navsystemstatus, Ros.navsystemstate.running.name(), (int)NAVSTARTTIMEOUT, 50);
						
			// check if running
			if (state.equals(values.navsystemstatus, Ros.navsystemstate.running)) {
				if (settings.getBoolean(ManualSettings.useflash))
					app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.med.name()); // reduce cpu
				if (!state.equals(values.dockstatus, AutoDock.UNDOCKED))
					state.set(values.rosinitialpose, "0_0_0");
				Util.log("navigation running", this);
				return; // success
				
			} else stopNavigation(); // give up
		}}).start();
	}

	/** */
	public static void stopNavigation() {
		Util.log("stopping navigation");
		Util.systemCall("pkill roslaunch");

		if (state.equals(values.navsystemstatus, Ros.navsystemstate.stopped)) return;

		state.set(values.navsystemstatus, Ros.navsystemstate.stopping.name());
		new Thread(new Runnable() { public void run() {
			Util.delay(Ros.ROSSHUTDOWNDELAY);
			state.set(values.navsystemstatus, Ros.navsystemstate.stopped.name());
		}}).start();
	}

	/** */ 
	public static void dock() {
		if (state.getBoolean(values.autodocking)){
			app.driverCallServer(PlayerCommands.messageclients, "autodocking in progress, command dropped");
			return;
		}
		else if (state.equals(values.dockstatus, AutoDock.DOCKED)) {
			app.driverCallServer(PlayerCommands.messageclients, "already docked, command dropped");
			return;
		}
		else if (!state.equals(values.navsystemstatus, Ros.navsystemstate.running)) {
			app.driverCallServer(PlayerCommands.messageclients, "navigation not running");
			return;
		}

		SystemWatchdog.waitForCpu();
		Ros.setWaypointAsGoal(DOCK);
		state.set(values.roscurrentgoal, "pending");

		new Thread(new Runnable() { public void run() {

			long start = System.currentTimeMillis();// store goal coords
			while (state.equals(values.roscurrentgoal, "pending") && System.currentTimeMillis() - start < 1000) Util.delay(10);
			if (!state.exists(values.roscurrentgoal)) return; // avoid null pointer
			String goalcoords = state.get(values.roscurrentgoal);

			// wait to reach waypoint
			start = System.currentTimeMillis();
			while (System.currentTimeMillis() - start < WAYPOINTTIMEOUT && state.exists(values.roscurrentgoal)) {
				try {
					if(!state.equals(values.roscurrentgoal, goalcoords)){
						Util.log("Navigation.dock(): waypoint changed while waiting", this);
						return;
					}
				} catch (Exception e) {Util.printError(e);}
				Util.delay(10);
			}

			if ( !state.exists(values.rosgoalstatus)) { 
				//this is (harmlessly) thrown normally nav goal cancelled (by driver stop command?)
				Util.log("error, rosgoalstatus null, setting to empty string", this); 
				state.set(values.rosgoalstatus, "");
			}

			if (!state.equals(values.rosgoalstatus, Ros.ROSGOALSTATUS_SUCCEEDED)) {
				app.driverCallServer(PlayerCommands.messageclients, "Navigation.dock() failed to reach dock");
				failed = true;
				return;
			}

			navdockactive = true;
			Util.delay(1000);

			// success, should be pointing at dock, shut down nav
			stopNavigation();
			Util.delay(Ros.ROSSHUTDOWNDELAY/2); 
			// 5000 too low, massive cpu sometimes here
			SystemWatchdog.waitForCpu();
			SystemWatchdog.waitForCpu();

			if (! navdockactive) return;

			SystemWatchdog.waitForCpu();
			app.comport.checkisConnectedBlocking(); // just in case
			app.driverCallServer(PlayerCommands.odometrystop, null); // just in case, odo messes up docking if ros not killed

			// camera, lights

			// highres
			app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.high.name());
			// only switch mode if camera not running, to avoid interruption of feed
			if (state.equals(values.stream, Application.streamstate.stop.name()) ||
					state.equals(values.stream, Application.streamstate.mic.name())) {
				app.driverCallServer(PlayerCommands.videosoundmode, Application.VIDEOSOUNDMODELOW); // saves CPU
				app.driverCallServer(PlayerCommands.publish, Application.streamstate.camera.name());
			}
			app.driverCallServer(PlayerCommands.spotlight, "0");
			app.driverCallServer(PlayerCommands.cameracommand, ArduinoPrime.cameramove.reverse.name());
			app.driverCallServer(PlayerCommands.floodlight, Integer.toString(AutoDock.FLHIGH));
			app.driverCallServer(PlayerCommands.left, "180");
			Util.delay(app.comport.fullrotationdelay/2 + 4000);  // tried changing this to 4000, didn't help

			if (!navdockactive) return;

			// TODO: debugging potential intermittent camera problem .. conincides with flash error 1009? checked, no 1009
//			state.delete(State.values.lightlevel);
//			app.driverCallServer(PlayerCommands.getlightlevel, null);
//			long timeout = System.currentTimeMillis() + 5000;
//			while (!state.exists(State.values.lightlevel) && System.currentTimeMillis() < timeout)  Util.delay(10);
//			if (state.exists(State.values.lightlevel)) Util.log("lightlevel: "+state.get(State.values.lightlevel), this);
//			else Util.log("error, lightlevel null", this);

			SystemWatchdog.waitForCpu(30, 20000); // stricter 30% check, lots of missed dock grabs here

			// make sure dock in view before calling autodock go
			if (!finddock(AutoDock.HIGHRES, false)) { // single highres dock search, no rotate to start
				if (!finddock(AutoDock.LOWRES, true)) { // lowres dock search with rotate (lowres much faster)
					Util.log("error, finddock() needs to try 2nd time", this);
					Util.delay(20000); // allow cam shutdown, system settle
					app.killGrabber(); // force chrome restart
					Util.delay(Application.GRABBERRESPAWN + 4000); // allow time for grabber respawn
					// camera, lights (in case malg had dropped commands)
					app.driverCallServer(PlayerCommands.spotlight, "0");
					app.driverCallServer(PlayerCommands.cameracommand, ArduinoPrime.cameramove.reverse.name());
					app.driverCallServer(PlayerCommands.floodlight, Integer.toString(AutoDock.FLHIGH));
					app.driverCallServer(PlayerCommands.publish, Application.streamstate.camera.name());
					Util.delay(4000); // wait for cam startup, light adjust
					app.comport.checkisConnectedBlocking(); // just in case
					if (!navdockactive) return;
					if (!finddock(AutoDock.HIGHRES, true)) return; // highres dock search with rotate (slow)
				}
			}

			
			SystemWatchdog.waitForCpu(); // onwards
			app.driverCallServer(PlayerCommands.autodock, autodockmodes.go.name());

			// ------------------------------------------------------------------------------------------------------------------------
			// wait while autodocking does its thing
			// state.block(values.autodocking, "true", (int)SystemWatchdog.AUTODOCKTIMEOUT, 100);
			state.block(values.autodocking, "false", (int)SystemWatchdog.AUTODOCKTIMEOUT, 100);
			
			//start = System.currentTimeMillis();
			//while (state.getBoolean(values.autodocking) &&
			///		System.currentTimeMillis() - start < SystemWatchdog.AUTODOCKTIMEOUT) Util.delay(100);

			if (!navdockactive) return;

			if (state.equals(values.dockstatus, AutoDock.DOCKED)) {
				Util.delay(2000);
				app.driverCallServer(PlayerCommands.publish, Application.streamstate.stop.name());
			} else  Util.log("Navigation.dock() - unable to dock", this);
		}}).start();

		navdockactive = false;
	}

	// dock detect, rotate if necessary
	private static boolean finddock(String resolution, boolean rotate) {
		int rot = 0;
	
		SystemWatchdog.waitForCpu(); // added stricter 40% check, lots of missed dock grabs here

		while (navdockactive) {
			SystemWatchdog.waitForCpu();
			app.driverCallServer(PlayerCommands.dockgrab, resolution);
			long start = System.currentTimeMillis();
			while (!state.exists(values.dockfound.name()) && System.currentTimeMillis() - start < Util.ONE_MINUTE)
				Util.delay(10);  // wait

			if (state.getBoolean(values.dockfound)) break; // great, onwards
			else if (!rotate) return false;
			else { // rotate a bit
				app.comport.checkisConnectedBlocking(); // just in case
				app.driverCallServer(PlayerCommands.right, "25");
				Util.delay(10); // thread safe

				start = System.currentTimeMillis();
				while(!state.equals(values.direction, ArduinoPrime.direction.stop.name())
						&& System.currentTimeMillis() - start < 5000) { Util.delay(10); } // wait
				Util.delay(ArduinoPrime.TURNING_STOP_DELAY);
			}
			rot ++;

			if (rot == 1) Util.log("Navigation.finddock(): error, rotation required");

			if (rot == 21) { // failure give up
//					callForHelp(subject, body);
				app.driverCallServer(PlayerCommands.publish, Application.streamstate.stop.name());
				app.driverCallServer(PlayerCommands.floodlight, "0");
				app.driverCallServer(PlayerCommands.messageclients, "Navigation.finddock() failed to find dock");
				return false;
			}
		}
		if (!navdockactive) return false;
		return true;
	}
	
	/** */
	public static void runActiveRoute(){
		String route = NavigationUtilities.getActiveRoute();
		if( route != null ){
			runRoute(route);
			Util.log("Navigation.runActiveRoute(): Auto-starting nav route: "+route);
		}
	}
	
	/** */
	private static boolean updateTimeToNextRoute(final Element navroute){ 
		
		// get schedule info, map days to numbers
		NodeList days = navroute.getElementsByTagName("day");
		if (days.getLength() == 0) {
			app.driverCallServer(PlayerCommands.messageclients, "Can't schedule route, no days specified");
			cancelAllRoutes();
			return true;
		}
		
		int[] daynums = new int[days.getLength()];
		String[] availabledays = new String[]{"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
		for (int d=0; d<days.getLength(); d++) {
			for (int ad = 0; ad<availabledays.length; ad++) {
				if (days.item(d).getTextContent().equalsIgnoreCase(availabledays[ad]))   daynums[d]=ad+1;
			}
		}
		// more schedule info
		int starthour = Integer.parseInt(navroute.getElementsByTagName("starthour").item(0).getTextContent());
		int startmin = Integer.parseInt(navroute.getElementsByTagName("startmin").item(0).getTextContent());
		int routedurationhours = Integer.parseInt(navroute.getElementsByTagName("routeduration").item(0).getTextContent());
		
		Calendar calendarnow = Calendar.getInstance();
		calendarnow.setTime(new Date());
		int daynow = calendarnow.get(Calendar.DAY_OF_WEEK); // 1-7 (friday is 6)
		boolean startroute = false;
		int nextdayindex = 99;
		for (int i=0; i<daynums.length; i++) {
			// check if need to start run right away
			if (daynums[i] == daynow -1 || daynums[i] == daynow || (daynums[i]==7 && daynow == 1)) { // yesterday or today
				Calendar testday = Calendar.getInstance();
				if (daynums[i] == daynow -1 || (daynums[i]==7 && daynow == 1)) { // yesterday
					testday.set(calendarnow.get(Calendar.YEAR), calendarnow.get(Calendar.MONTH),
							calendarnow.get(Calendar.DATE) - 1, starthour, startmin);
				} else { // today
					testday.set(calendarnow.get(Calendar.YEAR), calendarnow.get(Calendar.MONTH),
							calendarnow.get(Calendar.DATE), starthour, startmin);
				}
				if (calendarnow.getTimeInMillis() >= testday.getTimeInMillis() && calendarnow.getTimeInMillis() <
						testday.getTimeInMillis() + (routedurationhours * 60 * 60 * 1000)) {
					startroute = true;
					break;
				}
			}

			if (daynow == daynums[i]) nextdayindex = i;
			else if (daynow > daynums[i]) nextdayindex = i+1;
		}

		// determine seconds to next route
		if (!state.exists(values.nextroutetime)) { // only set once

			int adddays = 0;
			if (nextdayindex >= daynums.length ) { //wrap around
				nextdayindex = 0;
				adddays = 7-daynow + daynums[0];
			}
			else adddays = daynums[nextdayindex] - daynow;

			Calendar testday = Calendar.getInstance();
			testday.set(calendarnow.get(Calendar.YEAR), calendarnow.get(Calendar.MONTH),
					calendarnow.get(Calendar.DATE) + adddays, starthour, startmin);

			if (testday.getTimeInMillis() < System.currentTimeMillis()) { // same day, past route
				nextdayindex ++;
				if (nextdayindex >= daynums.length ) { //wrap around
					adddays = 7-daynow + daynums[0];
				}
				else  adddays = daynums[nextdayindex] - daynow;
				testday.set(calendarnow.get(Calendar.YEAR), calendarnow.get(Calendar.MONTH),
						calendarnow.get(Calendar.DATE) + adddays, starthour, startmin);
			}
			else if (testday.getTimeInMillis() - System.currentTimeMillis() > Util.ONE_DAY*7) //wrap
				testday.setTimeInMillis(testday.getTimeInMillis()-Util.ONE_DAY*7);

			state.set(values.nextroutetime, testday.getTimeInMillis());	
		}
		return startroute;
	}
	
	/** go to each waypoint */
	private synchronized static void visitWaypoints(final String name){
 
		if(failed) {
			Util.log("visitWaypoints("+ name + ") failed flag set on before startup, return.");
			return;
		}
		
		final NodeList waypoints = navroute.getElementsByTagName("waypoint");	    	
    	int wpnum = 0;
    	
    	// TODO: CHANGE TO FOR LOOP
    	while(wpnum < waypoints.getLength()){
    		
    		if(failed){
				Util.log("visitWaypoints(" + name + "): failed breaking loop");
				return;
			}
			
    		// check if cancelled while waiting
			if( ! state.exists(values.navigationroute)){
				Util.log("visitWaypoints(" + name + "): cancelled breaking loop");
				return;
			}
			
			// TODO: UGLY ----------------------------------------------------------------------------------------------------------------
    		String wpname = ((Element) waypoints.item(wpnum)).getElementsByTagName("wpname").item(0).getTextContent();

			app.comport.checkisConnectedBlocking(); // just in case
    		if(wpname.equals(DOCK)) break;

			SystemWatchdog.waitForCpu();
			Util.log("setting waypoint: "+wpname);
    		if (!Ros.setWaypointAsGoal(wpname)) { // can't set waypoint, try the next one
				NavigationLog.newItem(NavigationLog.ERRORSTATUS, "unable to set waypoint", wpname, name);
				app.driverCallServer(PlayerCommands.messageclients, "route "+name+" unable to set waypoint");
				wpnum ++;
				continue;
    		}

    		state.set(values.roscurrentgoal, "pending");
    		
    		// wait to reach wayypoint
			long start = System.currentTimeMillis();
			while(state.exists(values.roscurrentgoal) && System.currentTimeMillis() - start < WAYPOINTTIMEOUT) Util.delay(1000);
			
			// check if cancelled while waiting
			if( ! state.exists(values.navigationroute)){
				Util.log("visitWaypoints(" + name + "): canceled..  breaking loop");
				return;
			}
    		
			if(failed){
				Util.log("visitWaypoints(" + name + "): failed..  breaking loop");
				return;
			}
    		
					
			// this is (harmlessly) thrown normally nav goal cancelled (by driver stop command?)
			if( ! state.exists(values.rosgoalstatus)){ 
				Util.log("error, state rosgoalstatus null");
				state.set(values.rosgoalstatus, "error"); 
				failed = true;
			}
			
			if( ! state.get(values.rosgoalstatus).equals(Ros.ROSGOALSTATUS_SUCCEEDED)){
				NavigationLog.newItem(NavigationLog.ERRORSTATUS, "Failed to reach waypoint: "+wpname, wpname, name);
				app.driverCallServer(PlayerCommands.messageclients, "route "+name+" failed to reach waypoint");
				wpnum ++;
				failed = true;
				continue; 
			}

    		if(failed){
				Util.log("visitWaypoints(" + name + "): failed breaking loop");
				return;
			}
    		
			Util.log("visitWaypoints(" + name + "): start process waypoint: " + wpname);
			
			// send actions and duration delay to processRouteActions()
			NodeList actions = ((Element) waypoints.item(wpnum)).getElementsByTagName("action");
			long duration = Long.parseLong(
				((Element) waypoints.item(wpnum)).getElementsByTagName("duration").item(0).getTextContent());
			if (duration > 0)  processWayPointActions(actions, duration * 1000, wpname, name);
			
			Util.fine("visitWaypoints(" + name + "): done process waypoint: " + wpname);
			
			SystemWatchdog.waitForCpu(); // help ros localize b
			app.driverCallServer(PlayerCommands.left, "360");
			Util.delay((long) (360 / state.getDouble(values.odomturndpms.name())) + 1000);
			SystemWatchdog.waitForCpu();
			wpnum ++;
		}
    	
    	// check if cancelled while waiting
		if( ! state.exists(values.navigationroute)) return;
		dock();
		
		// wait while autodocking does its thing 
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < SystemWatchdog.AUTODOCKTIMEOUT + WAYPOINTTIMEOUT) {
			if( ! state.exists(values.navigationroute)) return;
			if(state.get(values.dockstatus).equals(AutoDock.DOCKED) && !state.getBoolean(values.autodocking)) break; 
			Util.delay(100); // success
		}
			
		if( ! state.get(values.dockstatus).equals(AutoDock.DOCKED)){
			NavigationLog.newItem(NavigationLog.ERRORSTATUS, "Unable to dock"); 
			
			// try docking one more time, sending alert if fail
			stopNavigation();
			Util.log("navigation is off, trying redock()");
			Util.delay(Ros.ROSSHUTDOWNDELAY / 2); // 5000 too low, massive cpu sometimes here
			app.driverCallServer(PlayerCommands.redock, SystemWatchdog.NOFORWARD);
	
			if(!delayToNextRoute(name)) return;
		}
		
		Util.fine("visitWaypoints(" + name + "): exit.......");
	}
	
	/** ------------------------------------------------------------------------------ working --------------------------------------------- */
	// TODO: build error checking into this (ignore duplicate waypoints, etc)
	// assume goto dock at the end, whether or not dock is a waypoint

	public static void runRoute(final String name){
		
		Util.log("runRoute().. runRoute: " + name);

		if(state.getBoolean(values.autodocking)){
			app.driverCallServer(PlayerCommands.messageclients, "command dropped, autodocking");
			return;
		}

		if(state.equals(values.dockstatus, AutoDock.UNDOCKED)){ 
			app.driverCallServer(PlayerCommands.messageclients, "Can't start route, location unknown, command dropped");
			cancelAllRoutes();
			return;
		}
		
		Vector<String> routeNames = NavigationUtilities.getRoutes();
		if( ! routeNames.contains(name)){
			app.driverCallServer(PlayerCommands.messageclients, "route: "+name+" not found");
			return;
		}
		
		// set as active and start route
		if(state.exists(values.navigationroute)){
			Util.log(".. route in progress, need to cancel first:  "+state.get(values.navigationroute));
			cancelAllRoutes();
			state.block(values.navigationroute, 5000);
			Util.log(".. done cancel first, now set active.. ");
	//		Util.delay(3000);
		}
		NavigationUtilities.setActiveRoute(name);		
		state.set(values.navigationroute, name);
		navroute = NavigationUtilities.getRouteElement(name);
	
		new Thread(new Runnable() { public void run() {
			
			Util.log("Route: " + name + "  Activated by user");
			NavigationLog.newItem("Route: " + name + "  Activated by user");
			app.driverCallServer(PlayerCommands.messageclients, "activating route: " + name);
			
			while(true){ // repeat route schedule forever until cancelled
				
				// determine next scheduled route time, wait if necessary
				state.delete(values.nextroutetime);
				while (state.exists(values.navigationroute)){
					Util.delay(1000);
					if(failed) return;
					if(updateTimeToNextRoute(navroute)){
						state.delete(State.values.nextroutetime);
						break; // delete so not shown in gui 
					}
				}

				// check if cancelled while waiting
				if( ! state.exists(State.values.navigationroute)){
					Util.fine("..runRoute (canceled): " + name);
					return;
				}
				
//				if( ! state.exists(values.navigationroute) || NavigationUtilities.getActiveRoute() != null){
//					Util.fine("..runRoute (canceled): " + name);
//					return;
//				}
	
				if(failed){
					Util.fine("..runRoute (failed): " + name);
					return;
				}
				
				// developer 
				if(settings.getBoolean(ManualSettings.developer.name())){
					if(batteryTooLow()){
						Util.log("battery too low: " + state.get(values.batterylife) + " needs to be: " + MIN_BATTERY_LIFE, this);
//						NavigationLog.newItem("Battery too low to start: " + state.get(values.batterylife));
						if( ! delayToNextRoute(name)) return;
						continue;
					}
				}
				
				if( ! waitForNavSystem()){ 
					NavigationLog.newItem(NavigationLog.ERRORSTATUS, "unable to start navigation system");
					if(state.getUpTime() > Util.TEN_MINUTES){
						app.driverCallServer(PlayerCommands.reboot, null);
						return;
					}

					if( ! delayToNextRoute(name)) return;
					continue;
				}

				// check if cancelled while waiting
				if( ! state.exists(values.navigationroute)) return;
				if(failed){
					Util.fine("..runRoute (failed): " + name);
					return;
				}
				
				// start 
				app.driverCallServer(PlayerCommands.cameracommand, ArduinoPrime.cameramove.horiz.name());
				routestarttime = System.currentTimeMillis(); 
				state.delete(values.recoveryrotation);
				state.delete(values.routeoverdue);
				routemillimeters = 0; // count distance from dock too
//				rotations = 0;
				failed = false;
				
				SystemWatchdog.waitForCpu(); 
				undockandlocalize();
				SystemWatchdog.waitForCpu(); 
				visitWaypoints(name);	
				
				if(failed){
					
					 Util.log("route failed: " + name  , this);
					 NavigationUtilities.routeFailed(name);
					 NavigationLog.newItem(NavigationLog.ERRORSTATUS, "route failed: reason??? ");
					 return;
				
				 } else {
					
					 Util.log("route completed: " + name + " " + (int)(System.currentTimeMillis() - routestarttime)/1000 + " seconds " + (int)routemillimeters/1000 + " meters"  , this);
					 NavigationUtilities.routeCompleted(name, (int)(System.currentTimeMillis() - routestarttime)/1000, (int)routemillimeters/1000);
					 NavigationLog.newItem(NavigationLog.COMPLETEDSTATUS, null);
					 consecutiveroute++;

					if(!delayToNextRoute(name)) return;
				 }					
				
				Util.log("..runRoute (bottom of loop): " + name + " #" + consecutiveroute, this);
			}
		}}).start();
		
		Util.fine("..runRoute (exit): " + name);
	}

	private static void undockandlocalize() { // blocking
		state.set(State.values.motionenabled, true);
		double distance = settings.getDouble(ManualSettings.undockdistance);
		app.driverCallServer(PlayerCommands.forward, String.valueOf(distance));
		Util.delay((long) (distance / state.getDouble(values.odomlinearmpms.toString()))); // required for fast systems?!
		long start = System.currentTimeMillis();
		while(!state.get(values.direction).equals(ArduinoPrime.direction.stop.toString())
				&& System.currentTimeMillis() - start < 10000) { Util.delay(10); } // wait
		Util.delay(ArduinoPrime.LINEAR_STOP_DELAY);

		// rotate to localize
		app.comport.checkisConnectedBlocking(); // pcb could reset changing from wall to battery
		app.driverCallServer(PlayerCommands.left, "360");
		Util.delay((long) (360 / state.getDouble(State.values.odomturndpms.toString())) + 1000);
	}

	private static boolean delayToNextRoute(final String name){
		
		if(navroute == null || name == null){
			Util.log("delayToNextRoute(): bad params");
			return false;
		}
		
		String msg = " min until next route: "+name+", run #"+consecutiveroute;
		if (consecutiveroute > RESTARTAFTERCONSECUTIVEROUTES) 
			msg = " min until reboot, max consecutive routes: "+RESTARTAFTERCONSECUTIVEROUTES+ " reached";

		String min = navroute.getElementsByTagName("minbetween").item(0).getTextContent();
		long timebetween = Long.parseLong(min) * 1000 * 60;
		state.set(values.nextroutetime, System.currentTimeMillis()+timebetween);
		app.driverCallServer(PlayerCommands.messageclients, min +  msg);
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < timebetween) {
			if( ! state.exists(values.navigationroute)) {
				state.delete(values.nextroutetime);
				return false;
			}

			if( ! state.exists(values.navigationroute)){
				state.delete(values.nextroutetime);
				return false;
			}

//			if( failed ){
//				Util.log("delayToNextRoute(): failed, return..");
//				return false;
//			}
			
			Util.delay(1000);
		}

		if ((consecutiveroute > RESTARTAFTERCONSECUTIVEROUTES) && (state.getUpTime() > Util.TEN_MINUTES)){ 
			Util.log("rebooting, max consecutive routes reached"); // prevent runaway reboots
			app.driverCallServer(PlayerCommands.reboot, null);
			return false;
		}
		
		return true;
	}

	/**
	 * process actions for single waypoint 
	 */
	private static void processWayPointActions(NodeList actions, long duration, String wpname, String name) {
		
		// TODO: actions here
		//  <action>  
		// var navrouteavailableactions = ["rotate", "email", "rss", "motion", "sound", "human", "not detect" ];
		/*
		 * rotate only works with motion & human (ie., camera) ignore otherwise
		 *     -rotate ~30 deg increments, fixed duration. start-stop
		 *     -minimum full rotation, or more if <duration> allows 
		 * <duration> -- cancel all actions and move to next waypoint (let actions complete)
		 * alerts: rss or email: send on detection (or not) from "motion", "human", "sound"
		 *      -once only from single waypoint, max 2 per route (on 1st detection, then summary at end)
		 * if no alerts, log only
		 */
    	// takes 5-10 seconds to init if mic is on (mic only, or mic + camera)

		
		boolean rotate = false;
		boolean email = false;
		boolean rss = false;
		boolean motion = false;
		boolean notdetect = false;
		boolean sound = false;
		boolean human = false;
		boolean photo = false;
		boolean record = false;
		
		boolean camera = false;
		boolean mic = false;
		String notdetectedaction = "";
		
    	for (int i=0; i < actions.getLength(); i++) {
    		String action = ((Element) actions.item(i)).getTextContent();
    		switch (action) {
				case "rotate": rotate = true; break;
				case "email": email = true; break;
				case "rss": rss = true; break;
				case "motion":
					motion = true;
					camera = true;
					notdetectedaction = action;
					break;
				case "not detect":
					notdetect = true;
					break;
				case "sound":
					sound = true;
					mic = true;
					notdetectedaction = action;
					break;
				case "human":
					human = true;
					camera = true;
					notdetectedaction = action;
					break;
				case "photo":
					photo = true;
					camera = true;
					break;

				case "record video":
					record = true;
					camera = true;
					mic = true;
					break;
			}
    	}

		// if no camera, what's the point in rotating
    	if (!camera && rotate) {
			rotate = false;
			app.driverCallServer(PlayerCommands.messageclients, "rotate action ignored, camera unused");
		}

    	// VIDEOSOUNDMODELOW required for flash stream activity function to work, saves cpu for camera
    	String previousvideosoundmode = state.get(values.videosoundmode);
    	if (mic || camera) app.driverCallServer(PlayerCommands.videosoundmode, Application.VIDEOSOUNDMODELOW);
    	
		// setup camera mode and position
		if (camera) {
			if (human) app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.med.name());
			else if (motion) app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.high.name());
			else app.driverCallServer(PlayerCommands.streamsettingscustom, "1280_720_8_85");
			if (photo) app.driverCallServer(PlayerCommands.camtilt, String.valueOf(ArduinoPrime.CAM_HORIZ-ArduinoPrime.CAM_NUDGE*2));
			else app.driverCallServer(PlayerCommands.camtilt, String.valueOf(ArduinoPrime.CAM_HORIZ-ArduinoPrime.CAM_NUDGE*5));

			if (human)
				app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.med.name());
			else if (motion)
    			app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.high.name());
			else if (photo)
				app.driverCallServer(PlayerCommands.streamsettingscustom, "1280_720_8_85");
			else // record
				app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.high.name());

			if (photo)app.driverCallServer(PlayerCommands.camtilt, String.valueOf(ArduinoPrime.CAM_HORIZ - ArduinoPrime.CAM_NUDGE * 2));
			else app.driverCallServer(PlayerCommands.camtilt, String.valueOf(ArduinoPrime.CAM_HORIZ-ArduinoPrime.CAM_NUDGE*3));
			if (human) app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.med.name());
			else if (motion) app.driverCallServer(PlayerCommands.streamsettingsset, Application.camquality.high.name());
			else app.driverCallServer(PlayerCommands.streamsettingscustom, "1280_720_8_85");
			if (photo) app.driverCallServer(PlayerCommands.camtilt, String.valueOf(ArduinoPrime.CAM_HORIZ - ArduinoPrime.CAM_NUDGE * 2));
			else app.driverCallServer(PlayerCommands.camtilt, String.valueOf(ArduinoPrime.CAM_HORIZ-ArduinoPrime.CAM_NUDGE*5));
		}
																				
		// turn on cam and or mic, allow delay for normalize
		if (camera && mic) {
			app.driverCallServer(PlayerCommands.publish, Application.streamstate.camandmic.name());
			Util.delay(5000);
			if (!settings.getBoolean(ManualSettings.useflash)) Util.delay(5000); // takes a while for 2 streams
		} else if (camera && !mic) {
			app.driverCallServer(PlayerCommands.publish, Application.streamstate.camera.name());
			Util.delay(5000);
		} else if (!camera && mic) {
			app.driverCallServer(PlayerCommands.publish, Application.streamstate.mic.name());
			Util.delay(5000);
		}

		String recordlink = null;
		if (record)  recordlink = app.video.record(Settings.TRUE); // start recording

		long waypointstart = System.currentTimeMillis();
		long delay = 10000;
 		if (duration < delay) duration = delay;
		int turns = 0;
		int maxturns = 8;
		if (!rotate) {
			delay = duration;
			turns = maxturns;
		}

		// remain at waypoint rotating and/or waiting, detection running if enabled
		while (System.currentTimeMillis() - waypointstart < duration || turns < maxturns) {

			if( ! state.exists(values.navigationroute)) return;
//	    	if( ! state.equals(values.navigationrouteid, id)) return;

			state.delete(values.streamactivity);

			// enable sound detection
			if (sound) {
				if (!settings.getBoolean(ManualSettings.useflash))   app.video.sounddetect(Settings.TRUE);
				else app.driverCallServer(PlayerCommands.setstreamactivitythreshold,
						"0 " + settings.readSetting(ManualSettings.soundthreshold));
			}

			// lights on if needed
			boolean lightondelay = false;
			if (camera) {
				if (turnLightOnIfDark()) {
					Util.delay(4000); // allow cam to adjust
					lightondelay = true;
				}
			}

			// enable human or motion detection
			if (human) app.driverCallServer(PlayerCommands.objectdetect, OpenCVObjectDetect.HUMAN);
			else if (motion) app.driverCallServer(PlayerCommands.motiondetect, null);

			// mic takes a while to start up
			if (sound && !lightondelay) Util.delay(2000);

			// ALL SENSES ENABLED, NOW WAIT
			long start = System.currentTimeMillis();
			while (!state.exists(values.streamactivity) && System.currentTimeMillis() - start < delay
					/*&& state.get(values.navigationrouteid).equals(id) */ ) { Util.delay(10); }

			// PHOTO
			if (photo) {
				if (!settings.getBoolean(ManualSettings.useflash))  SystemWatchdog.waitForCpu();

				final String link = FrameGrabHTTP.saveToFile("");

				Util.delay(2000); // allow time for framgrabusy flag to be set true
				long timeout = System.currentTimeMillis() + 10000;
				while (state.getBoolean(values.framegrabbusy) && System.currentTimeMillis() < timeout) Util.delay(10);
				Util.delay(3000); // allow time to download

				String navlogmsg = "<a href='" + link + "' target='_blank'>Photo</a>";
				String msg = "[Oculus Prime Photo] ";
				msg += navlogmsg+", time: "+ Util.getTime()+", at waypoint: " + wpname + ", route: " + name;

				if (email) {
					String emailto = settings.readSetting(GUISettings.email_to_address);
					if (!emailto.equals(Settings.DISABLED)){
						app.driverCallServer(PlayerCommands.email, emailto + " " + msg);
						navlogmsg += "<br> email sent ";
						
						// TODO: TESTING.. adjust path send as an attachment
						// new SendMail("Oculus Prime Photo", msg, new String[]{ link });
					}
				}
				if (rss) {
					app.driverCallServer(PlayerCommands.rssadd, msg);
					navlogmsg += "<br> new RSS item ";
				}

				NavigationLog.newItem(NavigationLog.PHOTOSTATUS, navlogmsg, wpname, state.get(values.navigationroute));
			}

			// ALERT
			if (state.exists(values.streamactivity) && ! notdetect) {

				String streamactivity =  state.get(values.streamactivity);
				String msg = "Detected: "+streamactivity+", time: "+ Util.getTime()+", at waypoint: " + wpname + ", route: " + name;
				Util.log(msg + " " + streamactivity);
				String navlogmsg = "Detected: "+streamactivity;

				String link = "";
				if (streamactivity.contains("video") || streamactivity.contains(OpenCVObjectDetect.HUMAN)) {
					link = FrameGrabHTTP.saveToFile("?mode=processedImgJPG");
					navlogmsg += "<br><a href='" + link + "' target='_blank'>image link</a>";
				}

				if (email || rss) {

					if (streamactivity.contains("video") || streamactivity.contains(OpenCVObjectDetect.HUMAN)) {
						msg = "[Oculus Prime Detected "+streamactivity+"] " + msg;
						msg += "\nimage link: " + link + "\n";
						Util.delay(3000); // allow time for download thread to capture image before turning off camera
					}
					else if (streamactivity.contains("audio")) {
						msg = "[Oculus Prime Sound Detection] Sound " + msg;
					}

					if (email) {
						String emailto = settings.readSetting(GUISettings.email_to_address);
						if (!emailto.equals(Settings.DISABLED)) {
							app.driverCallServer(PlayerCommands.email, emailto + " " + msg);
							navlogmsg += "<br> email sent ";
						}
					}
					if (rss) {
						app.driverCallServer(PlayerCommands.rssadd, msg);
						navlogmsg += "<br> new RSS item ";
					}
				}

				NavigationLog.newItem(NavigationLog.ALERTSTATUS, navlogmsg);

				// shut down sensing
				if (state.exists(values.motiondetect))
					app.driverCallServer(PlayerCommands.motiondetectcancel, null);
				if (state.exists(values.objectdetect))
					app.driverCallServer(PlayerCommands.objectdetectcancel, null);
				if (sound) {
					if (!settings.getBoolean(ManualSettings.useflash))   // app.video.sounddetect(Settings.FALSE);
						app.driverCallServer(PlayerCommands.sounddetect, Settings.FALSE);
					else   app.driverCallServer(PlayerCommands.setstreamactivitythreshold, "0 0");
				}

				break; // go to next waypoint, stop if rotating
			}

			// nothing detected, shut down sensing
			if (state.exists(values.motiondetect))
				app.driverCallServer(PlayerCommands.motiondetectcancel, null);
			if (state.exists(values.objectdetect))
				app.driverCallServer(PlayerCommands.objectdetectcancel, null);
			if (sound) {
				if (!settings.getBoolean(ManualSettings.useflash))   // app.video.sounddetect(Settings.FALSE);
					app.driverCallServer(PlayerCommands.sounddetect, Settings.FALSE);
				else   app.driverCallServer(PlayerCommands.setstreamactivitythreshold, "0 0");
			}

			// ALERT if not detect
			if (notdetect) {
				String navlogmsg = "NOT Detected: "+notdetectedaction;
				String msg = "";

				if (email || rss) {
					msg = "[Oculus Prime: "+notdetectedaction+" NOT detected] ";
					msg += "At waypoint: " + wpname + ", route: " + name + ", time: "+Util.getTime();
				}

				if (email) {
					String emailto = settings.readSetting(GUISettings.email_to_address);
					if (!emailto.equals(Settings.DISABLED)) {
						app.driverCallServer(PlayerCommands.email, emailto + " " + msg);
						navlogmsg += "<br> email sent ";
					}
				}
				
				if (rss) {
					app.driverCallServer(PlayerCommands.rssadd, msg);
					navlogmsg += "<br> new RSS item ";
				}

				NavigationLog.newItem(NavigationLog.ALERTSTATUS, navlogmsg);
			}

			if (rotate) {
				Util.delay(2000);
				SystemWatchdog.waitForCpu(8000); // lots of missed stop commands, cpu timeouts here
				double degperms = state.getDouble(values.odomturndpms.name());   // typically 0.0857;
				app.driverCallServer(PlayerCommands.move, ArduinoPrime.direction.left.name());
				Util.delay((long) (50.0 / degperms));
				app.driverCallServer(PlayerCommands.move, ArduinoPrime.direction.stop.name());

				long stopwaiting = System.currentTimeMillis()+750; // timeout if error
				while(!state.get(values.direction).equals(ArduinoPrime.direction.stop.name()) &&
						System.currentTimeMillis() < stopwaiting) { Util.delay(1); } // wait for stop
				if (!state.get(values.direction).equals(ArduinoPrime.direction.stop.name()))
					Util.log("error, missed turnstop within 750ms");

				Util.delay(4000); // 2000 if condition below enabled
				turns ++;
			}
		}

		// END RECORD
		
		if (record && recordlink != null) {

			String navlogmsg = "<a href='" + recordlink + "_video.flv' target='_blank'>Video</a>";
			if (!settings.getBoolean(ManualSettings.useflash))
				navlogmsg += "<br><a href='" + recordlink + "_audio.flv' target='_blank'>Audio</a>";
			String msg = "[Oculus Prime Video] ";
			msg += navlogmsg+", time: "+Util.getTime()+", at waypoint: " + wpname + ", route: " + name;

			if (email) {
				String emailto = settings.readSetting(GUISettings.email_to_address);
				if (!emailto.equals(Settings.DISABLED)) {
					app.driverCallServer(PlayerCommands.email, emailto + " " + msg);
					navlogmsg += "<br> email sent ";
				}
			}
			if (rss) {
				app.driverCallServer(PlayerCommands.rssadd, msg);
				navlogmsg += "<br> new RSS item ";
			}
			NavigationLog.newItem(NavigationLog.VIDEOSTATUS, navlogmsg);
			app.record(Settings.FALSE); // stop recording
		}

		app.driverCallServer(PlayerCommands.publish, Application.streamstate.stop.name());
		if (camera) {
			app.driverCallServer(PlayerCommands.spotlight, "0");
			app.driverCallServer(PlayerCommands.cameracommand, ArduinoPrime.cameramove.horiz.name());
		}
		if (mic) app.driverCallServer(PlayerCommands.videosoundmode, previousvideosoundmode) ;
	}

	public static boolean turnLightOnIfDark(){
		
		if (state.getInteger(values.spotlightbrightness) == 100) return false; // already on
		
		state.delete(values.lightlevel);
		app.driverCallServer(PlayerCommands.getlightlevel, null); // get new value 
		state.block(values.lightlevel, 5000);

		// be sure 
		if(state.getInteger(values.lightlevel) == State.ERROR) {
			app.driverCallServer(PlayerCommands.getlightlevel, null);	state.block(values.lightlevel, 5000);
			Util.fine(" 0 -- error -- Navigation.turnLightOnIfDark(): light too low = " + state.getInteger(values.lightlevel));
		}
		
		if(state.getInteger(values.lightlevel) < 30){
			Util.fine(" 1 Navigation.turnLightOnIfDark(): light too low = " + state.getInteger(values.lightlevel));
			
			if(state.getInteger(values.lightlevel) == State.ERROR) app.driverCallServer(PlayerCommands.getlightlevel, null);
			state.block(values.lightlevel, 5000);

			if(state.getInteger(values.lightlevel) < 30){

				Util.fine(" 2 Navigation.turnLightOnIfDark(): light too low = " + state.getInteger(values.lightlevel));

				app.driverCallServer(PlayerCommands.spotlight, "100"); // light on
				return true;
			}
		}
		
		return false;
	}

	public static void goalCancel(){
		state.set(values.rosgoalcancel, true); // pass info to ros node
		state.delete(values.roswaypoint);
	}
	
	public static void cancelAllRoutes(){	
		
		if( ! state.exists(values.navigationroute)){
			Util.log("..........skipping, nothing active");

			return;
		}
		
		else {
			
			Util.log("...........all routes cancelled, avtive == " + NavigationUtilities.getActiveRoute(), null);

			
		}
		
		failed = true;
		navroute = null;
		goalCancel();
		state.delete(values.nextroutetime);
		NavigationUtilities.deactivateAllRoutes();
		Util.log("all routes cancelled", null);
		
	
		
		app.driverCallServer(PlayerCommands.messageclients, "all routes cancelled");
	}

	public static void saveMap() {
		if (!state.get(values.navsystemstatus).equals(Ros.navsystemstate.mapping.name())) {
			app.message("unable to save map, mapping not running", null, null);
			return;
		}
		new Thread(new Runnable() { public void run() {
			if (Ros.saveMap()) app.message("map saved to "+Ros.getMapFilePath(), null, null);
		}  }).start();
	}
}

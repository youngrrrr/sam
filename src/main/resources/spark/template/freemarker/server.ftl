<#assign content>
<div id="server-gui">
	<div>
		<div id="tweetBtn" style='float:left; margin-top:5px;'>
			<script>
				window.twttr=(function(d,s,id){var js,fjs=d.getElementsByTagName(s)[0],t=window.twttr||{};if(d.getElementById(id))return;js=d.createElement(s);js.id=id;js.src="https://platform.twitter.com/widgets.js";fjs.parentNode.insertBefore(js,fjs);t._e=[];t.ready=function(f){t._e.push(f);};return t;}(document,"script","twitter-wjs"));
			</script>
		</div></br>
		<h2 id="server-title" class="title" style="margin-top:-10px;">Server</h2>
		<div id='songs-wrapper'>
			<label>Songs</label></br>
			<div class="btn-group" style="width: 100%;">
				<input type="search" id="song-search" class="form-control" placeholder="Search by song, album, or artist"></input>
				<span id="search-clear" class="glyphicon glyphicon-remove-circle"></span>
			</div>
			<div id="songs-bound-div-2">
			
			</div>
		</div>
		<div id="queue-wrapper">
			<label>Queue</label></br>
			<div id="songs-bound-div-3">
				<div id='queue-div' style='margin-top: 10px;'></div>
			</div>
		</div>
		<div id="server-canvas">
			<label for="clientpositions">Client Positions</label></br>
			<div id="clients-canvas">
			</div>
			</br>
			<div class="btn-group" role="btn-group-justified">
				<button class="btn btn-default btn-primary" id="clear-focus">Clear Focus</button>
				<button class="btn btn-default btn-primary" id="mute">Mute</button>
				<a class="btn btn-default btn-primary" href="/songs" role="button" target="_blank">Select Directory</a>
			</div>
		</div>
	</div>
	</br>
	<div style="clear: both; position: fixed; min-width: 1100px; width: 80%; bottom: 0; left: 10%; float: left;">
		<div id="song-controls">
			<div id='current-song' style='float:left;width:70px;height:70px;'></div>
			<button id="pause-play"></button>
			<p style="float: left;">0:00</p>
			<div id="progressbar">
  				<div></div>
			</div>
			<p style="float: left;">0:00</p>
			<button id="skip"></button>
		</div>
	</div>
</div>
</#assign>
<#assign pagescripts>
	<script src="js/socket.io.js"></script>
	<script src="js/d3.js"></script>
	<script src="js/server.js"></script>
	<script src="http://cdn.peerjs.com/0.3/peer.js"></script>
</#assign>
<#include "main.ftl">
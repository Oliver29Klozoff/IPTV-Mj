# Test-only loopback server; synthetic media and placeholder account. Never use real provider credentials.
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse,parse_qs
from pathlib import Path
import json,os
os.chdir(Path(__file__).resolve().parent.parent / "app/src/androidTest/assets")
class Handler(SimpleHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_GET(self):
  query=parse_qs(urlparse(self.path).query); action=query.get('action',[''])[0]
  if self.path.startswith('/player_api.php'):
   data={'user_info':{'auth':1,'status':'Active','username':'fixture','password':'fixture','max_connections':'1','active_cons':'0'},'server_info':{'url':'127.0.0.1','port':'18770','server_protocol':'http'}}
   if action=='get_live_streams': data=[{'stream_id':991,'name':'MKTV Launcher Test Channel','num':1,'category_id':'1','tv_archive':0,'direct_source':'http://127.0.0.1:18770/fixture.mp4'}]
   elif action=='get_vod_streams': data=[{'stream_id':992,'name':'MKTV Resume Test Movie','container_extension':'mp4','category_id':'1'}]
   elif action=='get_vod_info': data={'info':{'name':'MKTV Resume Test Movie'},'movie_data':{'stream_id':992,'name':'MKTV Resume Test Movie','container_extension':'mp4'}}
   elif action=='get_series': data=[{'series_id':993,'name':'MKTV Next Episode Test','category_id':'1'}]
   elif action=='get_series_info': data={'info':{'name':'MKTV Next Episode Test'},'seasons':[{'season_number':1,'name':'Season 1'}],'episodes':{'1':[{'id':str(9930+i),'episode_num':i,'title':'MKTV Episode S01E0'+str(i),'season':1,'container_extension':'mp4'} for i in (1,2)]}}
   elif action.endswith('_categories'): data=[{'category_id':'1','category_name':'Fixtures','parent_id':0}]
   elif action: data=[]
   raw=json.dumps(data).encode(); self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw);return
  self.path='/launcher-fixture.mp4';super().do_GET()
ThreadingHTTPServer(('127.0.0.1',18770),Handler).serve_forever()

"""Compile and test the Android collection engine on the JVM; --live probes news sources."""
import argparse,os,pathlib,subprocess,sys
root=pathlib.Path(__file__).resolve().parents[2]
args=argparse.ArgumentParser();args.add_argument('--live',action='store_true');options=args.parse_args()
subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('prepare-tests.py'))],check=True)
tools=root/'.cache'/'android';jdk=next((tools/'jdk').glob('*/bin'),None)
if jdk is None:raise SystemExit('Run python scripts/android/prepare.py first.')
classes=root/'.cache'/'android-tests';classes.mkdir(parents=True,exist_ok=True)
tmp=root/'.cache'/'tmp';tmp.mkdir(parents=True,exist_ok=True)
reports=root/'.local'/'reports'
env=os.environ.copy();env['JAVA_TOOL_OPTIONS']='-Duser.home='+str(tools/'java-home')+' -Djava.io.tmpdir='+str(tmp)
sources=[root/'android'/'src'/'cn'/'yilan'/'news'/name for name in ['NewsEngine.java','NewsState.java','NewsArchive.java','FeaturedNews.java','ReadingRules.java','ReaderTheme.java']]
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','8','-classpath',str(tools/'json-test.jar'),'-d',str(classes),*map(str,sources),str(root/'android'/'tests'/'NativeEngineTest.java')],env=env,check=True)
command=[str(jdk/'java.exe'),'-classpath',str(classes)+os.pathsep+str(tools/'json-test.jar'),'cn.yilan.news.NativeEngineTest',str(root/'config'/'sources.json')]
if options.live:
 reports.mkdir(parents=True,exist_ok=True)
 command+=['--live',str(reports/'native-direct-check.json')]
subprocess.run(command,env=env,check=True)

# Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

# Different utility functions used accross scripts

import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

ANDROID_JAR_DIR = 'third_party/android_jar/lib-v{api}'
ANDROID_JAR = os.path.join(ANDROID_JAR_DIR, 'android.jar')
TOOLS_DIR = os.path.abspath(os.path.normpath(os.path.join(__file__, '..')))
REPO_ROOT = os.path.realpath(os.path.join(TOOLS_DIR, '..'))
THIRD_PARTY = os.path.join(REPO_ROOT, 'third_party')
MEMORY_USE_TMP_FILE = 'memory_use.tmp'
DEX_SEGMENTS_RESULT_PATTERN = re.compile('- ([^:]+): ([0-9]+)')
BUILD = os.path.join(REPO_ROOT, 'build')
BUILD_DEPS_DIR = os.path.join(BUILD, 'deps')
BUILD_MAIN_DIR = os.path.join(BUILD, 'classes', 'main')
BUILD_TEST_DIR = os.path.join(BUILD, 'classes', 'test')
LIBS = os.path.join(BUILD, 'libs')
GENERATED_LICENSE_DIR = os.path.join(BUILD, 'generatedLicense')
SRC_ROOT = os.path.join(REPO_ROOT, 'src', 'main', 'java')

D8 = 'd8'
R8 = 'r8'
R8LIB = 'r8lib'
R8LIB_NO_DEPS = 'r8LibNoDeps'
R8_SRC = 'sourceJar'
COMPATDX = 'compatdx'
COMPATDXLIB = 'compatdxlib'
COMPATPROGUARD = 'compatproguard'
COMPATPROGUARDLIB = 'compatproguardlib'

D8_JAR = os.path.join(LIBS, 'd8.jar')
R8_JAR = os.path.join(LIBS, 'r8.jar')
R8LIB_JAR = os.path.join(LIBS, 'r8lib.jar')
R8_SRC_JAR = os.path.join(LIBS, 'r8-src.jar')
R8LIB_EXCLUDE_DEPS_JAR = os.path.join(LIBS, 'r8lib-exclude-deps.jar')
R8_FULL_EXCLUDE_DEPS_JAR = os.path.join(LIBS, 'r8-full-exclude-deps.jar')
COMPATDX_JAR = os.path.join(LIBS, 'compatdx.jar')
COMPATDXLIB_JAR = os.path.join(LIBS, 'compatdxlib.jar')
COMPATPROGUARD_JAR = os.path.join(LIBS, 'compatproguard.jar')
COMPATPROGUARDLIB_JAR = os.path.join(LIBS, 'compatproguardlib.jar')
MAVEN_ZIP = os.path.join(LIBS, 'r8.zip')
GENERATED_LICENSE = os.path.join(GENERATED_LICENSE_DIR, 'LICENSE')
RT_JAR = os.path.join(REPO_ROOT, 'third_party/openjdk/openjdk-rt-1.8/rt.jar')
R8LIB_KEEP_RULES = os.path.join(REPO_ROOT, 'src/main/keep.txt')
RETRACE_JAR = os.path.join(THIRD_PARTY, 'proguard', 'proguard6.0.1', 'lib', 'retrace.jar')

# Common environment setup.
USER_HOME = os.path.expanduser('~')
ANDROID_HOME = os.path.join(USER_HOME, 'Android', 'Sdk')
ANDROID_BUILD_TOOLS_VERSION = '28.0.3'
ANDROID_BUILD_TOOLS = os.path.join(
    ANDROID_HOME, 'build-tools', ANDROID_BUILD_TOOLS_VERSION)

def Print(s, quiet=False):
  if quiet:
    return
  print(s)

def Warn(message):
  CRED = '\033[91m'
  CEND = '\033[0m'
  print(CRED + message + CEND)

def PrintCmd(cmd, env=None, quiet=False):
  if quiet:
    return
  if type(cmd) is list:
    cmd = ' '.join(cmd)
  if env:
    env = ' '.join(['{}=\"{}\"'.format(x, y) for x, y in env.iteritems()])
    print('Running: {} {}'.format(env, cmd))
  else:
    print('Running: {}'.format(cmd))
  # I know this will hit os on windows eventually if we don't do this.
  sys.stdout.flush()

class ProgressLogger(object):
  CLEAR_LINE = '\033[K'
  UP = '\033[F'

  def __init__(self, quiet=False):
    self._count = 0
    self._has_printed = False
    self._quiet = quiet

  def log(self, text):
    if self._quiet:
      if self._has_printed:
        sys.stdout.write(ProgressLogger.UP + ProgressLogger.CLEAR_LINE)
      if len(text) > 140:
        text = text[0:140] + '...'
    print(text)
    self._has_printed = True

  def done(self):
    if self._quiet and self._has_printed:
      sys.stdout.write(ProgressLogger.UP + ProgressLogger.CLEAR_LINE)
      print('')
      sys.stdout.write(ProgressLogger.UP)

def RunCmd(cmd, env_vars=None, quiet=False):
  PrintCmd(cmd, env=env_vars, quiet=quiet)
  env = os.environ.copy()
  if env_vars:
    env.update(env_vars)
  process = subprocess.Popen(
      cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
  stdout = []
  logger = ProgressLogger(quiet=quiet)
  failed = False
  while True:
    line = process.stdout.readline()
    if line != b'':
      stripped = line.rstrip()
      stdout.append(stripped)
      logger.log(stripped)

      # TODO(christofferqa): r8 should fail with non-zero exit code.
      if ('AssertionError:' in stripped
          or 'CompilationError:' in stripped
          or 'CompilationFailedException:' in stripped
          or 'Compilation failed' in stripped):
        failed = True
    else:
      logger.done()
      exit_code = process.poll()
      if exit_code or failed:
        for line in stdout:
          Warn(line)
        raise subprocess.CalledProcessError(
            exit_code, cmd, output='\n'.join(stdout))
      return stdout

def IsWindows():
  return os.name == 'nt'

def DownloadFromX20(sha1_file):
  download_script = os.path.join(REPO_ROOT, 'tools', 'download_from_x20.py')
  cmd = [download_script, sha1_file]
  PrintCmd(cmd)
  subprocess.check_call(cmd)

def DownloadFromGoogleCloudStorage(sha1_file, bucket='r8-deps'):
  suffix = '.bat' if IsWindows() else ''
  download_script = 'download_from_google_storage%s' % suffix
  cmd = [download_script, '-n', '-b', bucket, '-u', '-s',
         sha1_file]
  PrintCmd(cmd)
  subprocess.check_call(cmd)

def get_sha1(filename):
  sha1 = hashlib.sha1()
  with open(filename, 'rb') as f:
    while True:
      chunk = f.read(1024*1024)
      if not chunk:
        break
      sha1.update(chunk)
  return sha1.hexdigest()

def is_master():
  remotes = subprocess.check_output(['git', 'branch', '-r', '--contains',
                                     'HEAD'])
  return 'origin/master' in remotes

def get_HEAD_sha1():
  cmd = ['git', 'rev-parse', 'HEAD']
  PrintCmd(cmd)
  with ChangedWorkingDirectory(REPO_ROOT):
    return subprocess.check_output(cmd).strip()

def makedirs_if_needed(path):
  try:
    os.makedirs(path)
  except OSError:
    if not os.path.isdir(path):
        raise

def upload_dir_to_cloud_storage(directory, destination, is_html=False, public_read=True):
  # Upload and make the content encoding right for viewing directly
  cmd = ['gsutil.py', 'cp']
  if is_html:
    cmd += ['-z', 'html']
  if public_read:
    cmd += ['-a', 'public-read']
  cmd += ['-R', directory, destination]
  PrintCmd(cmd)
  subprocess.check_call(cmd)

def upload_file_to_cloud_storage(source, destination, public_read=True):
  cmd = ['gsutil.py', 'cp']
  if public_read:
    cmd += ['-a', 'public-read']
  cmd += [source, destination]
  PrintCmd(cmd)
  subprocess.check_call(cmd)

def delete_file_from_cloud_storage(destination):
  cmd = ['gsutil.py', 'rm', destination]
  PrintCmd(cmd)
  subprocess.check_call(cmd)

def ls_files_on_cloud_storage(destination):
  cmd = ['gsutil.py', 'ls', destination]
  PrintCmd(cmd)
  return subprocess.check_output(cmd)

def cat_file_on_cloud_storage(destination, ignore_errors=False):
  cmd = ['gsutil.py', 'cat', destination]
  PrintCmd(cmd)
  try:
    return subprocess.check_output(cmd)
  except subprocess.CalledProcessError as e:
    if ignore_errors:
      return ''
    else:
      raise e

def file_exists_on_cloud_storage(destination):
  cmd = ['gsutil.py', 'ls', destination]
  PrintCmd(cmd)
  return subprocess.call(cmd) == 0

def download_file_from_cloud_storage(source, destination):
  cmd = ['gsutil.py', 'cp', source, destination]
  PrintCmd(cmd)
  subprocess.check_call(cmd)

def create_archive(name):
  tarname = '%s.tar.gz' % name
  with tarfile.open(tarname, 'w:gz') as tar:
    tar.add(name)
  return tarname

def extract_dir(filename):
  return filename[0:len(filename) - len('.tar.gz')]

def unpack_archive(filename):
  dest_dir = extract_dir(filename)
  if os.path.exists(dest_dir):
    print 'Deleting existing dir %s' % dest_dir
    shutil.rmtree(dest_dir)
  dirname = os.path.dirname(os.path.abspath(filename))
  with tarfile.open(filename, 'r:gz') as tar:
    tar.extractall(path=dirname)

# Note that gcs is eventually consistent with regards to list operations.
# This is not a problem in our case, but don't ever use this method
# for synchronization.
def cloud_storage_exists(destination):
  cmd = ['gsutil.py', 'ls', destination]
  PrintCmd(cmd)
  exit_code = subprocess.call(cmd)
  return exit_code == 0

class TempDir(object):
 def __init__(self, prefix=''):
   self._temp_dir = None
   self._prefix = prefix

 def __enter__(self):
   self._temp_dir = tempfile.mkdtemp(self._prefix)
   return self._temp_dir

 def __exit__(self, *_):
   shutil.rmtree(self._temp_dir, ignore_errors=True)

class ChangedWorkingDirectory(object):
 def __init__(self, working_directory, quiet=False):
   self._quiet = quiet
   self._working_directory = working_directory

 def __enter__(self):
   self._old_cwd = os.getcwd()
   if not self._quiet:
     print 'Enter directory:', self._working_directory
   os.chdir(self._working_directory)

 def __exit__(self, *_):
   if not self._quiet:
     print 'Enter directory:', self._old_cwd
   os.chdir(self._old_cwd)

# Reading Android CTS test_result.xml

class CtsModule(object):
  def __init__(self, module_name):
    self.name = module_name

class CtsTestCase(object):
  def __init__(self, test_case_name):
    self.name = test_case_name

class CtsTest(object):
  def __init__(self, test_name, outcome):
    self.name = test_name
    self.outcome = outcome

# Generator yielding CtsModule, CtsTestCase or CtsTest from
# reading through a CTS test_result.xml file.
def read_cts_test_result(file_xml):
  re_module = re.compile('<Module name="([^"]*)"')
  re_test_case = re.compile('<TestCase name="([^"]*)"')
  re_test = re.compile('<Test result="(pass|fail)" name="([^"]*)"')
  with open(file_xml) as f:
    for line in f:
      m = re_module.search(line)
      if m:
        yield CtsModule(m.groups()[0])
        continue
      m = re_test_case.search(line)
      if m:
        yield CtsTestCase(m.groups()[0])
        continue
      m = re_test.search(line)
      if m:
        outcome = m.groups()[0]
        assert outcome in ['fail', 'pass']
        yield CtsTest(m.groups()[1], outcome == 'pass')

def grep_memoryuse(logfile):
  re_vmhwm = re.compile('^VmHWM:[ \t]*([0-9]+)[ \t]*([a-zA-Z]*)')
  result = None
  with open(logfile) as f:
    for line in f:
      m = re_vmhwm.search(line)
      if m:
        groups = m.groups()
        s = len(groups)
        if s >= 1:
          result = int(groups[0])
          if s >= 2:
            unit = groups[1]
            if unit == 'kB':
              result *= 1024
            elif unit != '':
              raise Exception('Unrecognized unit in memory usage log: {}'
                  .format(unit))
  if result is None:
    raise Exception('No memory usage found in log: {}'.format(logfile))
  return result

# Return a dictionary: {segment_name -> segments_size}
def getDexSegmentSizes(dex_files):
  assert len(dex_files) > 0
  cmd = ['java', '-jar', R8_JAR, 'dexsegments']
  cmd.extend(dex_files)
  PrintCmd(cmd)
  output = subprocess.check_output(cmd)

  matches = DEX_SEGMENTS_RESULT_PATTERN.findall(output)

  if matches is None or len(matches) == 0:
    raise Exception('DexSegments failed to return any output for' \
        ' these files: {}'.format(dex_files))

  result = {}

  for match in matches:
    result[match[0]] = int(match[1])

  return result

def get_maven_path(version):
  return os.path.join('com', 'android', 'tools', 'r8', version)

def print_dexsegments(prefix, dex_files):
  for segment_name, size in getDexSegmentSizes(dex_files).items():
    print('{}-{}(CodeSize): {}'
        .format(prefix, segment_name, size))

# Ensure that we are not benchmarking with a google jvm.
def check_java_version():
  cmd= ['java', '-version']
  output = subprocess.check_output(cmd, stderr = subprocess.STDOUT)
  m = re.search('openjdk version "([^"]*)"', output)
  if m is None:
    raise Exception("Can't check java version: no version string in output"
        " of 'java -version': '{}'".format(output))
  version = m.groups(0)[0]
  m = re.search('google', version)
  if m is not None:
    raise Exception("Do not use google JVM for benchmarking: " + version)

def get_android_jar_dir(api):
  return os.path.join(REPO_ROOT, ANDROID_JAR_DIR.format(api=api))

def get_android_jar(api):
  return os.path.join(REPO_ROOT, ANDROID_JAR.format(api=api))

def get_android_optional_jars(api):
  android_optional_jars_dir = os.path.join(get_android_jar_dir(api), 'optional')
  android_optional_jars = [
    os.path.join(android_optional_jars_dir, 'android.test.base.jar'),
    os.path.join(android_optional_jars_dir, 'android.test.mock.jar'),
    os.path.join(android_optional_jars_dir, 'android.test.runner.jar'),
    os.path.join(android_optional_jars_dir, 'org.apache.http.legacy.jar')
  ]
  return [
      android_optional_jar for android_optional_jar in android_optional_jars
      if os.path.isfile(android_optional_jar)]

def is_bot():
  return 'BUILDBOT_BUILDERNAME' in os.environ

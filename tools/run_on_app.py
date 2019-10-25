#!/usr/bin/env python
# Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

from __future__ import print_function
from glob import glob
import copy
import optparse
import os
import sys
import time

import gmail_data
import gmscore_data
import golem
import nest_data
from sanitize_libraries import SanitizeLibraries
import toolhelper
import utils
import youtube_data
import chrome_data
import r8_data
import iosched_data

TYPES = ['dex', 'deploy', 'proguarded']
APPS = ['gmscore', 'nest', 'youtube', 'gmail', 'chrome', 'r8', 'iosched']
COMPILERS = ['d8', 'r8']
COMPILER_BUILDS = ['full', 'lib']

# We use this magic exit code to signal that the program OOM'ed
OOM_EXIT_CODE = 42
# According to Popen.returncode doc:
# A negative value -N indicates that the child was terminated by signal N.
TIMEOUT_KILL_CODE = -9

# Log file names
FIND_MIN_XMX_FILE = 'find_min_xmx_results'
FIND_MIN_XMX_DIR = 'find_min_xmx'

def ParseOptions(argv):
  result = optparse.OptionParser()
  result.add_option('--compiler',
                    help='The compiler to use',
                    choices=COMPILERS)
  result.add_option('--compiler-build',
                    help='Compiler build to use',
                    choices=COMPILER_BUILDS,
                    default='lib')
  result.add_option('--app',
                    help='What app to run on',
                    choices=APPS)
  result.add_option('--run-all',
                    help='Compile all possible combinations',
                    default=False,
                    action='store_true')
  result.add_option('--expect-oom',
                    help='Expect that compilation will fail with an OOM',
                    default=False,
                    action='store_true')
  result.add_option('--type',
                    help='Default for R8: deploy, for D8: proguarded',
                    choices=TYPES)
  result.add_option('--out',
                    help='Where to place the output',
                    default=utils.BUILD)
  result.add_option('--no-build',
                    help='Run without building first',
                    default=False,
                    action='store_true')
  result.add_option('--max-memory',
                    help='The maximum memory in MB to run with',
                    type='int')
  result.add_option('--find-min-xmx',
                    help='Find the minimum amount of memory we can run in',
                    default=False,
                    action='store_true')
  result.add_option('--find-min-xmx-min-memory',
                    help='Setting the minimum memory baseline to run in',
                    type='int')
  result.add_option('--find-min-xmx-max-memory',
                    help='Setting the maximum memory baseline to run in',
                    type='int')
  result.add_option('--find-min-xmx-range-size',
                    help='Setting the size of the acceptable memory range',
                    type='int',
                    default=32)
  result.add_option('--find-min-xmx-archive',
                    help='Archive find-min-xmx results on GCS',
                    default=False,
                    action='store_true')
  result.add_option('--timeout',
                    type='int',
                    default=0,
                    help='Set timeout instead of waiting for OOM.')
  result.add_option('--golem',
                    help='Running on golem, do not build or download',
                    default=False,
                    action='store_true')
  result.add_option('--ignore-java-version',
                    help='Do not check java version',
                    default=False,
                    action='store_true')
  result.add_option('--no-libraries',
                    help='Do not pass in libraries, even if they exist in conf',
                    default=False,
                    action='store_true')
  result.add_option('--no-debug',
                    help='Run without debug asserts.',
                    default=False,
                    action='store_true')
  result.add_option('--version',
                    help='The version of the app to run')
  result.add_option('-k',
                    help='Override the default ProGuard keep rules')
  result.add_option('--compiler-flags',
                    help='Additional option(s) for the compiler. ' +
                         'If passing several options use a quoted string.')
  result.add_option('--r8-flags',
                    help='Additional option(s) for the compiler. ' +
                         'Same as --compiler-flags, keeping it for backward'
                         ' compatibility. ' +
                         'If passing several options use a quoted string.')
  # TODO(tamaskenez) remove track-memory-to-file as soon as we updated golem
  # to use --print-memoryuse instead
  result.add_option('--track-memory-to-file',
                    help='Track how much memory the jvm is using while ' +
                    ' compiling. Output to the specified file.')
  result.add_option('--profile',
                    help='Profile R8 run.',
                    default=False,
                    action='store_true')
  result.add_option('--dump-args-file',
                    help='Dump a file with the arguments for the specified ' +
                    'configuration. For use as a @<file> argument to perform ' +
                    'the run.')
  result.add_option('--print-runtimeraw',
                    metavar='BENCHMARKNAME',
                    help='Print the line \'<BENCHMARKNAME>(RunTimeRaw):' +
                        ' <elapsed> ms\' at the end where <elapsed> is' +
                        ' the elapsed time in milliseconds.')
  result.add_option('--print-memoryuse',
                    metavar='BENCHMARKNAME',
                    help='Print the line \'<BENCHMARKNAME>(MemoryUse):' +
                        ' <mem>\' at the end where <mem> is the peak' +
                        ' peak resident set size (VmHWM) in bytes.')
  result.add_option('--print-dexsegments',
                    metavar='BENCHMARKNAME',
                    help='Print the sizes of individual dex segments as ' +
                        '\'<BENCHMARKNAME>-<segment>(CodeSize): <bytes>\'')
  result.add_option('--track-time-in-memory',
                    help='Plot the times taken from memory starting point to '
                         'end-point with defined memory increment',
                    default=False,
                    action='store_true')
  result.add_option('--track-time-in-memory-max',
                    help='Setting the maximum memory baseline to run in',
                    type='int')
  result.add_option('--track-time-in-memory-min',
                    help='Setting the minimum memory baseline to run in',
                    type='int')
  result.add_option('--track-time-in-memory-increment',
                    help='Setting the increment',
                    type='int',
                    default=32)

  return result.parse_args(argv)

# Most apps have -printmapping, -printseeds, -printusage and
# -printconfiguration in the Proguard configuration. However we don't
# want to write these files in the locations specified.
# Instead generate an auxiliary Proguard configuration placing these
# output files together with the dex output.
def GenerateAdditionalProguardConfiguration(temp, outdir):
  name = "output.config"
  with open(os.path.join(temp, name), 'w') as f:
    f.write('-printmapping ' + os.path.join(outdir, 'proguard.map') + "\n")
    f.write('-printseeds ' + os.path.join(outdir, 'proguard.seeds') + "\n")
    f.write('-printusage ' + os.path.join(outdir, 'proguard.usage') + "\n")
    f.write('-printconfiguration ' + os.path.join(outdir, 'proguard.config') + "\n")
    return os.path.abspath(f.name)

# Please add bug number for disabled permutations and please explicitly
# do Bug: #BUG in the commit message of disabling to ensure re-enabling
DISABLED_PERMUTATIONS = [
  # (app, version, type), e.g., ('gmail', '180826.15', 'deploy'),
  ('youtube', '13.37', 'deploy'), # b/120977564
]

def get_permutations():
  data_providers = {
      'gmscore': gmscore_data,
      'nest': nest_data,
      'youtube': youtube_data,
      'chrome': chrome_data,
      'gmail': gmail_data,
      'r8': r8_data,
      'iosched': iosched_data,
  }
  # Check to ensure that we add all variants here.
  assert len(APPS) == len(data_providers)
  for app, data in data_providers.iteritems():
    for version in data.VERSIONS:
      for type in data.VERSIONS[version]:
        if (app, version, type) not in DISABLED_PERMUTATIONS:
          for use_r8lib in [False, True]:
            yield app, version, type, use_r8lib

def run_all(options, args):
  # Args will be destroyed
  assert len(args) == 0
  for name, version, type, use_r8lib in get_permutations():
    compiler = 'r8' if type == 'deploy' else 'd8'
    compiler_build = 'lib' if use_r8lib else 'full'
    print('Executing %s/%s with %s %s %s' % (compiler, compiler_build, name,
      version, type))

    fixed_options = copy.copy(options)
    fixed_options.app = name
    fixed_options.version = version
    fixed_options.compiler = compiler
    fixed_options.compiler_build = compiler_build
    fixed_options.type = type
    exit_code = run_with_options(fixed_options, [])
    if exit_code != 0:
      print('Failed %s %s %s with %s/%s' % (name, version, type, compiler,
        compiler_build))
      exit(exit_code)

def find_min_xmx(options, args):
  # Args will be destroyed
  assert len(args) == 0
  # If we can run in 128 MB then we are good (which we can for small examples
  # or D8 on medium sized examples)
  if options.find_min_xmx_min_memory:
    not_working = options.find_min_xmx_min_memory
  elif options.compiler == 'd8':
    not_working = 128
  else:
    not_working = 1024
  if options.find_min_xmx_max_memory:
    working = options.find_min_xmx_max_memory
  else:
    working = 1024 * 8
  exit_code = 0
  range = options.find_min_xmx_range_size
  while working - not_working > range:
    next_candidate = working - ((working - not_working)/2)
    print('working: %s, non_working: %s, next_candidate: %s' %
          (working, not_working, next_candidate))
    extra_args = ['-Xmx%sM' % next_candidate]
    t0 = time.time()
    exit_code = run_with_options(options, [], extra_args)
    t1 = time.time()
    print('Running took: %s ms' % (1000.0 * (t1 - t0)))
    if exit_code != 0:
      if exit_code not in [OOM_EXIT_CODE, TIMEOUT_KILL_CODE]:
        print('Non OOM/Timeout error executing, exiting')
        return 2
    if exit_code == 0:
      working = next_candidate
    elif exit_code == TIMEOUT_KILL_CODE:
      print('Timeout. Continue to the next candidate.')
      not_working = next_candidate
    else:
      assert exit_code == OOM_EXIT_CODE
      not_working = next_candidate

  assert working - not_working <= range
  found_range = 'Found range: %s - %s' % (not_working, working)
  print(found_range)

  if options.find_min_xmx_archive:
    sha = utils.get_HEAD_sha1()
    (version, _) = get_version_and_data(options)
    destination = os.path.join(
        utils.R8_TEST_RESULTS_BUCKET,
        FIND_MIN_XMX_DIR,
        sha,
        options.compiler,
        options.compiler_build,
        options.app,
        version,
        get_type(options))
    gs_destination = 'gs://%s' % destination
    utils.archive_value(FIND_MIN_XMX_FILE, gs_destination, found_range + '\n')

  return 0

def print_min_xmx_ranges_for_hash(hash, compiler, compiler_build):
  app_directory = os.path.join(
      utils.R8_TEST_RESULTS_BUCKET,
      FIND_MIN_XMX_DIR,
      hash,
      compiler,
      compiler_build)
  gs_base = 'gs://%s' % app_directory
  for app in utils.ls_files_on_cloud_storage(gs_base).strip().split('\n'):
    for version in utils.ls_files_on_cloud_storage(app).strip().split('\n'):
      for type in utils.ls_files_on_cloud_storage(version).strip().split('\n'):
        gs_location = '%s%s' % (type, FIND_MIN_XMX_FILE)
        value = utils.cat_file_on_cloud_storage(gs_location, ignore_errors=True)
        print('%s\n' % value)

def track_time_in_memory(options, args):
  # Args will be destroyed
  assert len(args) == 0
  if not options.track_time_in_memory_min:
    raise Exception(
        'You have to specify --track_time_in_memory_min when running with '
        '--track-time-in-memory')
  if not options.track_time_in_memory_max:
    raise Exception(
        'You have to specify --track_time_in_memory_max when running with '
        '--track-time-in-memory')
  if not options.track_time_in_memory_increment:
    raise Exception(
        'You have to specify --track_time_in_memory_increment when running '
        'with --track-time-in-memory')
  current = options.track_time_in_memory_min
  print('Memory (KB)\tTime (ms)')
  with utils.TempDir() as temp:
    stdout = os.path.join(temp, 'stdout')
    stdout_fd = open(stdout, 'w')
    while current <= options.track_time_in_memory_max:
      extra_args = ['-Xmx%sM' % current]
      t0 = time.time()
      exit_code = run_with_options(options, [], extra_args, stdout_fd, quiet=True)
      t1 = time.time()
      total = (1000.0 * (t1 - t0)) if exit_code == 0 else -1
      print('%s\t%s' % (current, total))
      current += options.track_time_in_memory_increment

  return 0

def main(argv):
  (options, args) = ParseOptions(argv)
  if options.expect_oom and not options.max_memory:
    raise Exception(
        'You should only use --expect-oom if also specifying --max-memory')
  if options.expect_oom and options.timeout:
    raise Exception(
        'You should not use --timeout when also specifying --expect-oom')
  if options.find_min_xmx and options.track_time_in_memory:
    raise Exception(
        'You cannot both find the min xmx and track time at the same time')
  if options.run_all:
    return run_all(options, args)
  if options.find_min_xmx:
    return find_min_xmx(options, args)
  if options.track_time_in_memory:
    return track_time_in_memory(options, args)
  exit_code = run_with_options(options, args)
  if options.expect_oom:
    exit_code = 0 if exit_code == OOM_EXIT_CODE else 1
  return exit_code

def get_version_and_data(options):
  if options.app == 'gmscore':
    version = options.version or 'v9'
    data = gmscore_data
  elif options.app == 'nest':
    version = options.version or '20180926'
    data = nest_data
  elif options.app == 'youtube':
    version = options.version or '12.22'
    data = youtube_data
  elif options.app == 'chrome':
    version = options.version or '180917'
    data = chrome_data
  elif options.app == 'gmail':
    version = options.version or '170604.16'
    data = gmail_data
  elif options.app == 'r8':
    version = options.version or 'cf'
    data = r8_data
  elif options.app == 'iosched':
    version = options.version or '2019'
    data = iosched_data
  else:
    raise Exception("You need to specify '--app={}'".format('|'.join(APPS)))
  return version, data

def get_type(options):
  if not options.type:
    return 'deploy' if options.compiler == 'r8' else 'proguarded'
  return options.type

def run_with_options(options, args, extra_args=None, stdout=None, quiet=False):
  if extra_args is None:
    extra_args = []
  app_provided_pg_conf = False;
  # todo(121018500): remove when memory is under control
  if not any('-Xmx' in arg for arg in extra_args):
    if options.max_memory:
      extra_args.append('-Xmx%sM' % options.max_memory)
    else:
      extra_args.append('-Xmx8G')
  if options.golem:
    golem.link_third_party()
    options.out = os.getcwd()
  if not options.ignore_java_version:
    utils.check_java_version()

  outdir = options.out
  (version_id, data) = get_version_and_data(options)

  if options.compiler not in COMPILERS:
    raise Exception("You need to specify '--compiler={}'"
        .format('|'.join(COMPILERS)))

  if options.compiler_build not in COMPILER_BUILDS:
    raise Exception("You need to specify '--compiler-build={}'"
        .format('|'.join(COMPILER_BUILDS)))

  if not version_id in data.VERSIONS.keys():
    print('No version {} for application {}'
        .format(version_id, options.app))
    print('Valid versions are {}'.format(data.VERSIONS.keys()))
    return 1

  version = data.VERSIONS[version_id]

  type = get_type(options)

  if type not in version:
    print('No type {} for version {}'.format(type, version))
    print('Valid types are {}'.format(version.keys()))
    return 1
  values = version[type]
  inputs = None
  # For R8 'deploy' the JAR is located using the Proguard configuration
  # -injars option. For chrome and nest we don't have the injars in the
  # proguard files.
  if 'inputs' in values and (options.compiler != 'r8'
                             or type != 'deploy'
                             or options.app == 'chrome'
                             or options.app == 'nest'
                             or options.app == 'r8'
                             or options.app == 'iosched'):
    inputs = values['inputs']

  args.extend(['--output', outdir])
  if 'min-api' in values:
    args.extend(['--min-api', values['min-api']])

  if 'main-dex-list' in values:
    args.extend(['--main-dex-list', values['main-dex-list']])

  if options.compiler == 'r8':
    if 'pgconf' in values and not options.k:
      sanitized_lib_path = os.path.join(
          os.path.abspath(outdir), 'sanitized_lib.jar')
      sanitized_pgconf_path = os.path.join(
          os.path.abspath(outdir), 'sanitized.config')
      SanitizeLibraries(
          sanitized_lib_path, sanitized_pgconf_path, values['pgconf'])
      args.extend(['--pg-conf', sanitized_pgconf_path])
      app_provided_pg_conf = True
    if options.k:
      args.extend(['--pg-conf', options.k])
    if 'maindexrules' in values:
      for rules in values['maindexrules']:
        args.extend(['--main-dex-rules', rules])
    if 'allow-type-errors' in values:
      extra_args.append('-Dcom.android.tools.r8.allowTypeErrors=1')
    if 'proto-shrinking' in values:
      extra_args.append('-Dcom.android.tools.r8.fieldBitAccessAnalysis=1')
      extra_args.append('-Dcom.android.tools.r8.generatedExtensionRegistryShrinking=1')
      extra_args.append('-Dcom.android.tools.r8.generatedMessageLiteShrinking=1')
      extra_args.append('-Dcom.android.tools.r8.stringSwitchConversion=1')

  if not options.no_libraries and 'libraries' in values:
    for lib in values['libraries']:
      args.extend(['--lib', lib])

  if not outdir.endswith('.zip') and not outdir.endswith('.jar') \
      and not os.path.exists(outdir):
    os.makedirs(outdir)

  # Additional flags for the compiler from the configuration file.
  if 'flags' in values:
    args.extend(values['flags'].split(' '))
  if options.compiler == 'r8':
    if 'r8-flags' in values:
      args.extend(values['r8-flags'].split(' '))

  # Additional flags for the compiler from the command line.
  if options.compiler_flags:
    args.extend(options.compiler_flags.split(' '))
  if options.r8_flags:
    args.extend(options.r8_flags.split(' '))

  if inputs:
    args.extend(inputs)

  t0 = time.time()
  if options.dump_args_file:
    with open(options.dump_args_file, 'w') as args_file:
      args_file.writelines([arg + os.linesep for arg in args])
  else:
    with utils.TempDir() as temp:
      if options.print_memoryuse and not options.track_memory_to_file:
        options.track_memory_to_file = os.path.join(temp,
            utils.MEMORY_USE_TMP_FILE)
      if options.compiler == 'r8' and app_provided_pg_conf:
        # Ensure that output of -printmapping and -printseeds go to the output
        # location and not where the app Proguard configuration places them.
        if outdir.endswith('.zip') or outdir.endswith('.jar'):
          pg_outdir = os.path.dirname(outdir)
        else:
          pg_outdir = outdir
        additional_pg_conf = GenerateAdditionalProguardConfiguration(
            temp, os.path.abspath(pg_outdir))
        args.extend(['--pg-conf', additional_pg_conf])
      build = not options.no_build and not options.golem
      stderr_path = os.path.join(temp, 'stderr')
      with open(stderr_path, 'w') as stderr:
        if options.compiler_build == 'full':
          tool = options.compiler
        else:
          assert(options.compiler_build == 'lib')
          tool = 'r8lib-' + options.compiler
        exit_code = toolhelper.run(tool, args,
            build=build,
            debug=not options.no_debug,
            profile=options.profile,
            track_memory_file=options.track_memory_to_file,
            extra_args=extra_args,
            stdout=stdout,
            stderr=stderr,
            timeout=options.timeout,
            quiet=quiet)
      if exit_code != 0:
        with open(stderr_path) as stderr:
          stderr_text = stderr.read()
          if not quiet:
            print(stderr_text)
          if 'java.lang.OutOfMemoryError' in stderr_text:
            if not quiet:
              print('Failure was OOM')
            return OOM_EXIT_CODE
          return exit_code

      if options.print_memoryuse:
        print('{}(MemoryUse): {}'
            .format(options.print_memoryuse,
                utils.grep_memoryuse(options.track_memory_to_file)))

  if options.print_runtimeraw:
    print('{}(RunTimeRaw): {} ms'
        .format(options.print_runtimeraw, 1000.0 * (time.time() - t0)))

  if options.print_dexsegments:
    dex_files = glob(os.path.join(outdir, '*.dex'))
    utils.print_dexsegments(options.print_dexsegments, dex_files)
  return 0

if __name__ == '__main__':
  sys.exit(main(sys.argv[1:]))

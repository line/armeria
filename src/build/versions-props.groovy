/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

//
// This script generates version.properties, similar to Netty
//

def PROJECT_ARTIFACT_ID = project.artifactId
def PROPS_DIR_PATH = project.build.outputDirectory + "/META-INF"
def PROPS_FILE_PATH = PROPS_DIR_PATH + "/" + project.groupId + ".versions.properties";

println('Generate properties for ' + PROJECT_ARTIFACT_ID);

def keyOf = [
    version: PROJECT_ARTIFACT_ID + ".version",
    buildDate: PROJECT_ARTIFACT_ID + ".buildDate",
    longCommitHash : PROJECT_ARTIFACT_ID + ".longCommitHash",
    shortCommitHash : PROJECT_ARTIFACT_ID + ".shortCommitHash",
    commitDate : PROJECT_ARTIFACT_ID + ".commitDate",
    repoStatus : PROJECT_ARTIFACT_ID + ".repoStatus"
]

// default values are taken from Netty.
def valueOf = [
    version : project.version,
    buildDate : new Date().format("yyyy-MM-dd HH:mm:ss Z"), // same with git log date format
    longCommitHash : "0000000000000000000000000000000000000000",
    shortCommitHash : "0",
    commitDate : "1970-01-01 00:00:00 +0000",
    repoStatus : "unknown"
]

String executeCommand(String command) {
    def output = '';
    try {
        proc = command.execute();
        proc.waitFor();
        if (!proc.exitValue()) {
            output = proc.in.text;
        } else {
            println("'${command}' error ${proc.exitValue()}: ${proc.err.text}")
        }
    } catch (all) {
        print("Could not run '${command}': ")
        println(all);
    }
    return output;
}

def gitLogOut = executeCommand('git log -1 --format=format:%h%x20%H%x20%cd --date=iso')
if (gitLogOut) {
    println("Latest commit : ${gitLogOut}")
    def tokens = gitLogOut.tokenize(' ')
    valueOf.shortCommitHash = tokens[0]
    valueOf.longCommitHash = tokens[1]
    valueOf.commitDate = tokens[2..4].join(' ')
}

def gitStatusOut = executeCommand('git status --porcelain');
if (gitStatusOut) {
    valueOf.repoStatus = "dirty"
    print("Repository is dirty, for\n${gitStatusOut}")
} else {
    valueOf.repoStatus = "clean";
}

def props = new Properties()
File propsDir = new File(PROPS_DIR_PATH)
propsDir.mkdir();

File propsFile = new File(PROPS_FILE_PATH)
if (propsFile.exists()) {
    println("Update ${PROPS_FILE_PATH}")
    props.load(propsFile.newDataInputStream())
} else {
    println("Create ${PROPS_FILE_PATH}")
    propsFile.createNewFile();
}

keyOf.each { key, propName -> props.setProperty(propName, valueOf[key]) }
props.store(propsFile.newWriter(), null)

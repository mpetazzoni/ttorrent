#!/bin/bash
usage()
{
    cat <<HELP
    USAGE: run.sh [-h] [to|tr|cl] parms...
        -h : show this help message

        to : to run [To]rrent maker
        tr : to run [Tr]aker server
        cl : to run torrent [Cl]ient
        
        parms... : these are parameters passed to java command line 
        
        e.g.
        run.sh to spiderman.torrent http://192.168.6.1:6969/announce d:\\workflow\\data
        run.sh tr
        run.sh cl spiderman.torrent
HELP
    exit 0
}

#///////////////TEST/////////////////////////////////////////
[ $# == 0 ] && usage
[ $1 == '-h' ] && usage

# This shell script can run at cigwin and linux 
# distribution(such as ubuntu\fedora\centos\debian\...)
if [ -e '/bin/bash.exe' ] ; then
    SEPERATOR=';'
elif [ -e '/bin/bash' ] ; then
    SEPERATOR=':'
else
    echo 'This script does not support your system.'
    exit 0
fi

#///////////////REAL PART///////////////////////////////////
# Find all lib and add them to classpath
LIB_JARS=`find -L lib/ -name "*.jar" | tr [:space:] $SEPERATOR`

# Here are the programs to be executed
TORRENT='com.turn.ttorrent.common.Torrent'
TRACKER='com.turn.ttorrent.tracker.Tracker'
CLIENT='com.turn.ttorrent.client.Client'

# Add classes under bin derectory to classpath
LIB_JARS=$LIB_JARS'bin'

# Switch command
if [ $1 == 'to' ]
then
    OBJECT=$TORRENT
elif [ $1 == 'tr' ]
then
    OBJECT=$TRACKER
elif [ $1 == 'cl' ]
then
    OBJECT=$CLIENT
else
    echo 'unknow option : ' $1
    usage
fi

#/////////////////PARAMETERS FOR JAVA//////////////////////

# Shift the first paramter and pass all the last parameters
# to java command line
shift
PARAMS="$@"

#////////////////START UP//////////////////////////////////
java -cp $LIB_JARS $OBJECT $PARAMS


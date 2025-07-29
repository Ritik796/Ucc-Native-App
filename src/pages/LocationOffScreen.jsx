import { Image, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import React from 'react';

export default function LoadingOffScreen() {
    return (
        <View style={styles.container}>
            <View style={styles.div}>
                <View style={styles.netWorkContainer}>
                    <Image
                        source={require('../assets/images/imgLocationOff.png')}
                        style={styles.image}
                    />
                    <Text style={styles.textHead}>
                        Location Alert
                    </Text>
                    <View style={styles.text}>
                        <Text style={styles.infoText}>
                           Your location is off , Please turn on
                        </Text>
                        <Text style={styles.infoText}>
                         location.
                        </Text>

                    </View>
                </View>

            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        position: "absolute",
        zIndex: 10,
        backgroundColor: "#fff",
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        alignItems: "center",
        justifyContent: "center",
    },
    div: {
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        position: "relative"
    },
    netWorkContainer: {
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        marginBottom: 60
    },
    image: {
        width: 250,
        height: 250,
        resizeMode: 'contain',
        marginBottom: -20,
        borderRadius: 50
    },
    textHead: {
        color: 'black',
        fontSize: 22,
        textAlign: 'center',
        paddingHorizontal: 20,
        fontWeight: 500
    },
    infoText: {
        fontSize: 15,
        color: "black"
    },
    text: {
        display: "flex",
        justifyContent: "center",
        alignItems: "center"

    },
    
});

import { Image, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import React from 'react';

export default function NetworkOffScreen({ handleRetry }) {
    return (
        <View style={styles.container}>
            <View style={styles.div}>
                <View style={styles.netWorkContainer}>
                    <Image
                        source={require('../assets/images/noInternet.png')}
                        style={styles.image}
                    />
                    <Text style={styles.textHead}>
                        Network Alert
                    </Text>
                    <View style={styles.text}>
                        <Text style={styles.infoText}>
                            You are offline, Please check your 
                        </Text>
                        <Text style={styles.infoText}>
                            internet connection.
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
        marginBottom: 0,
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
    button: {
        position: "absolute",
        bottom: 20,
        backgroundColor: "#1f976f",
        width: '90%',
        display: 'flex',
        justifyContent: "center",
        alignItems: "center",
        padding: 15,
        borderRadius: 10,
    },
    buttonText: {
        color: "white",
        fontSize: 17,
        fontWeight: 500
    }

});

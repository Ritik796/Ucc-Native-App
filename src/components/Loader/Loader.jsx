import React from 'react';
import { View, Text, ActivityIndicator, StyleSheet } from 'react-native';

const Loader = () => {
  return (
    <View style={styles.container}>
      <ActivityIndicator size="large" color="green" />
      <Text style={styles.text}>Please wait...</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    position: "absolute",
    zIndex: 999,
    inset: 0,
    backgroundColor: 'rgba(0,0,0,0.3)',  // low opacity overlay
    justifyContent: "center",
    alignItems: "center",
  },
  text: {
    marginTop: 10,
    fontSize: 18,
    color: '#fff',   // white text for contrast on dark background
  },
});

export default Loader;

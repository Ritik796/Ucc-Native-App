import { StyleSheet } from 'react-native';


export const cameraStyle = StyleSheet.create({
  safeContainer: {
    flex: 1,
    position: "relative"

  },
  container: {
    flex: 1,
    paddingHorizontal: 1,
    position: "relative",
    justifyContent:"center",
    alignContent:"center"

  },
  header: {
    height: 60,
    backgroundColor: 'white',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: "center",
    paddingHorizontal: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#ccc'
  },
  headerLeft: {
    flexDirection: 'row',
    alignItems: 'center',

  },
  headerText: {
    fontSize: 18,
    color: 'black',
    fontWeight: 'bold',
    marginLeft: 18
  },
  body: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },

  cameraPreviewContainer: {
    flex: 1,
    width: '100%',
    backgroundColor: 'white',
    borderColor: '#ccc',
    borderWidth: 1,
    overflow: 'hidden',
  },
loaderOverlay: {
  position: 'absolute',
  top: 0,
  left: 0,
  right: 0,
  bottom: 0,
  justifyContent: 'center',
  alignItems: 'center',
  backgroundColor: 'rgba(0,0,0,0.3)', // low opacity to avoid GPU stress
  zIndex: 999,
},


  cameraContainer: {
    flex: 1,
    width: '100%',
  },
  footerBotttom: {
    padding: 8, // Or any height you want for footer
    alignItems: 'center',
    justifyContent: 'space-around',
    backgroundColor: '#fff', // Optional: visible footer background
    display: "flex",
    flexDirection: "row"
  },



  captureBtn: {
    backgroundColor: '#2ebf2e',
    width: '50%',
    height: 70,
    borderRadius: 15,
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 5,
    borderColor: 'white',
    flexDirection: "row"
  },

  captureTxt: {
    color: "white",
    fontSize: 20,
    marginLeft: 10
  },
  cancelTxt: {
    color: "white",
    fontSize: 20
  },
  iconCamera: {
    width: 30,
    height: 30,
  },
  switch: {
    position: 'absolute',
    bottom: 10,
    right: 10,
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
  },
  toggleCameraBtn: {
    backgroundColor: 'white',
    width: 45,
    height: 45,
    borderRadius: 30,
    justifyContent: 'center',
    alignItems: 'center',
  },
  iconSwitchCamera: {
    width: 30,
    height: 30,
  },
});

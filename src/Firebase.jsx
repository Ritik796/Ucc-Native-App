import { initializeApp } from "firebase/app";
import { getDatabase } from "firebase/database";

const firebaseConfig = {
  apiKey: "AIzaSyBGZ_IB4y5Ov1nuqIhWndGU8hfJadlE85I",
  authDomain: "dtdnavigator.firebaseapp.com",
  databaseURL: "https://dtdnavigatortesting.firebaseio.com", // No trailing slash
  projectId: "dtdnavigator",
  storageBucket: "dtdnavigator.appspot.com",
  messagingSenderId: "381118272786",
  appId: "1:381118272786:web:7721ceb096f806bcec0fcb",
  measurementId: "G-XJW1MRQ481",
};

const app = initializeApp(firebaseConfig);
export const getCurrentDatabase = (url) => {
  return getDatabase(app, url);
}



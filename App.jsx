//import liraries
import React, { useEffect, useState } from 'react';
import Splash from './src/pages/Splash';
import WebViewPage from './src/pages/WebViewPage';

// create a component
const App = () => {
  const [showSplash, setShowSplash] = useState(true);

  useEffect(() => {
    const timer = setTimeout(() => {
      // switch to web view
      setShowSplash(false);
    }, 2000);

    return () => clearTimeout(timer);
  }, []);

  return <>{showSplash ? <Splash /> : <WebViewPage />}</>;
};

//make this component available to the app
export default App;
